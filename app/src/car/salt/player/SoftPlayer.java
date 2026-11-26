package car.salt.player;

import android.content.Context;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.un4seen.bass.BASS;

/** Native audio decoders; Android is only the PCM output device. */
final class SoftPlayer {
    interface Prepared {void run(SoftPlayer p);}
    interface Complete {void run(SoftPlayer p);}
    interface Failed {boolean run(SoftPlayer p,int what,int extra);}
    private static boolean initialized;
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicInteger blocks=new AtomicInteger();
    private volatile boolean released;
    private volatile int stream;
    private volatile int pendingSeek=-1;
    private final AtomicInteger seekTicket=new AtomicInteger();
    private boolean notified,started,errored,logged;
    private Prepared prepared;private Complete complete;private Failed failed;
    private Uri source;private ParcelFileDescriptor descriptor;
    private volatile NetworkAudio network;
    private double gain;private boolean ducked;
    private PowerManager.WakeLock wake;
    private String reason="软解失败，尚未确定原因";
    private final Runnable timeout=()->error("软解打开或缓冲超时",-110);
    private final BASS.DSPPROC counter=(handle,channel,buffer,length,user)->{if(length>0)blocks.incrementAndGet();};
    private final BASS.SYNCPROC ended=(handle,channel,data,user)->main.post(()->{
        if(released||errored)return;
        if(network!=null&&network.failed()){error("网络音频数据读取中断",-1);return;}
        started=false;unlock();if(complete!=null)complete.run(this);
    });
    SoftPlayer(Context context){this.context=context.getApplicationContext();init(this.context);}
    private static synchronized void init(Context c){
        if(initialized)return;
        if(!BASS.BASS_Init(-1,44100,BASS.BASS_DEVICE_AUDIOTRACK))throw new IllegalStateException("PCM output init "+BASS.BASS_ErrorGetCode());
        try{
            if(!BASS.BASS_SetConfig(BASS.BASS_CONFIG_AM_DISABLE,1))throw new IllegalStateException("Cannot disable OS codecs");
            BASS.BASS_SetConfig(BASS.BASS_CONFIG_VIDEO,0);
            for(String lib:new String[]{"bass_aac","bassflac","bassalac","bassape","bassopus"}){
                if(BASS.BASS_PluginLoad(c.getApplicationInfo().nativeLibraryDir+"/lib"+lib+".so",0)==0)throw new IllegalStateException("Audio plugin "+lib+" error "+BASS.BASS_ErrorGetCode());
            }
            initialized=true;
        }finally{if(!initialized){BASS.BASS_PluginFree(0);BASS.BASS_Free();}}
    }
    void setAudioStreamType(int stream){}
    void setWakeMode(Context c,int mode){wake=((PowerManager)c.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ChiliPlayer:SoftDecode");wake.setReferenceCounted(false);}
    void setOnPreparedListener(Prepared listener){prepared=listener;}
    void setOnCompletionListener(Complete listener){complete=listener;}
    void setOnErrorListener(Failed listener){failed=listener;}
    void setDataSource(String address)throws Exception{setDataSource(context,address.contains("://")?Uri.parse(address):Uri.fromFile(new File(address)));}
    void setDataSource(Context c,Uri uri)throws Exception{
        if(source!=null)throw new IllegalStateException("source already assigned");
        source=uri;
    }
    private void openDescriptor()throws Exception{
        Exception last=null;
        for(Uri candidate:SourceAccess.candidates(context,source)){
            if(released)return;
            ParcelFileDescriptor opened=null;
            try{
                opened=context.getContentResolver().openFileDescriptor(candidate,"r");
                if(opened==null)throw new FileNotFoundException();
                android.system.Os.lseek(opened.getFileDescriptor(),0,android.system.OsConstants.SEEK_CUR);
                descriptor=opened;return;
            }catch(Exception ex){if(opened!=null)try{opened.close();}catch(IOException ignored){}last=ex;}
        }
        throw new IOException("文件读取或定位失败，需要兼容复制",last);
    }
    void prepareAsync(){
        if(source==null)throw new IllegalStateException("missing source");
        lock();main.postDelayed(timeout,30000);
        worker.execute(()->{
            try{
                if(released)return;
                if(!"http".equals(source.getScheme())&&!"https".equals(source.getScheme()))openDescriptor();
                if(released)return;
                int flags=BASS.BASS_SAMPLE_FLOAT|BASS.BASS_ASYNCFILE;
                int opened;
                if(descriptor!=null)opened=BASS.BASS_StreamCreateFile(descriptor,0,0,flags);
                else {NetworkAudio input=new NetworkAudio(context,source.toString());network=input;if(released){input.close();return;}opened=BASS.BASS_StreamCreateFileUser(BASS.STREAMFILE_NOBUFFER,flags,input,null);}
                if(opened==0){int code=BASS.BASS_ErrorGetCode();main.post(()->error("音频打开失败：读取、编码或容器问题（BASS "+code+"）",code));return;}
                stream=opened;
                if(released)return;
                BASS.BASS_CHANNELINFO info=new BASS.BASS_CHANNELINFO();
                // AM_DISABLE may retain an AAC/ADTS OS fallback. Reject OS streams before playback.
                if(!BASS.BASS_ChannelGetInfo(opened,info)||info.ctype==BASS.BASS_CTYPE_STREAM_AM)throw new IOException("System codec rejected");
                if(BASS.BASS_ChannelSetDSP(opened,counter,null,0)==0||BASS.BASS_ChannelSetSync(opened,BASS.BASS_SYNC_END,0,ended,null)==0)throw new IOException("Audio callbacks unavailable");
                main.post(()->{
                    if(released||errored)return;
                    notified=true;main.removeCallbacks(timeout);
                    Journal.event(context,"引擎=BASS "+Integer.toHexString(BASS.BASS_GetVersion())+"；"+(android.os.Process.is64Bit()?"ARM64":"ARM32")+" 音频；拒绝系统解码；ctype="+Integer.toHexString(info.ctype)+" plugin="+info.plugin+"；采样率="+info.freq+" 声道="+info.chans+"；PCM/AudioTrack");
                    if(prepared!=null)prepared.run(this);
                });
            }catch(Exception ex){main.post(()->error("软解初始化失败 · "+ex.getClass().getSimpleName(),-1));}
        });
    }
    void start(){if(released||!notified||errored)return;lock();applyGain();if(!BASS.BASS_ChannelPlay(stream,false)){error("PCM 播放启动失败",BASS.BASS_ErrorGetCode());return;}started=true;main.removeCallbacks(monitor);main.post(monitor);}
    void pause(){if(released)return;started=false;BASS.BASS_ChannelPause(stream);unlock();}
    void seekTo(int position){if(released||stream==0)return;final int target=Math.max(0,position),ticket=seekTicket.incrementAndGet();pendingSeek=target;worker.execute(()->{if(released||ticket!=seekTicket.get())return;boolean ok=BASS.BASS_ChannelSetPosition(stream,BASS.BASS_ChannelSeconds2Bytes(stream,target/1000.0),BASS.BASS_POS_BYTE);if(ticket==seekTicket.get())pendingSeek=-1;if(!ok)main.post(()->{if(!released)Journal.event(context,"音频定位失败；当前数据尚不可定位");});});}
    boolean isPlaying(){return !released&&started&&BASS.BASS_ChannelIsActive(stream)==BASS.BASS_ACTIVE_PLAYING;}
    int getCurrentPosition(){return pendingSeek>=0?pendingSeek:millis(BASS.BASS_ChannelGetPosition(stream,BASS.BASS_POS_BYTE));}
    int getDuration(){return millis(BASS.BASS_ChannelGetLength(stream,BASS.BASS_POS_BYTE));}
    private int millis(long bytes){if(released||stream==0||bytes<0)return 0;return (int)Math.max(0,Math.min(Integer.MAX_VALUE,BASS.BASS_ChannelBytes2Seconds(stream,bytes)*1000));}
    void setGain(double db){gain=Double.isNaN(db)||Double.isInfinite(db)?0:Math.max(-60,Math.min(20,db));if(notified)applyGain();}
    void setDucking(boolean value){ducked=value;if(notified&&!released)BASS.BASS_ChannelSetAttribute(stream,BASS.BASS_ATTRIB_VOL,ducked?0.2f:1f);}
    private void applyGain(){if(!BASS.BASS_ChannelSetAttribute(stream,BASS.BASS_ATTRIB_VOLDSP,(float)Math.pow(10,gain/20)))throw new IllegalStateException("PCM gain unavailable");}
    private final Runnable monitor=new Runnable(){public void run(){if(released||errored)return;if(network!=null&&network.failed()){error("网络音频读取失败",-1);return;}if(!logged&&blocks.get()>0){logged=true;Journal.event(context,"实际软件解码输出：PCM 音频块="+blocks.get()+"；BASS 原生 DSP 回调");}if(started)main.postDelayed(this,250);}};
    int decodedFrames(){return blocks.get();}
    String failureReason(){return reason;}
    private void lock(){if(wake!=null&&!wake.isHeld())wake.acquire();}
    private void unlock(){if(wake!=null&&wake.isHeld())wake.release();}
    private void error(String details,int code){if(released||errored)return;errored=true;started=false;reason=details;main.removeCallbacksAndMessages(null);unlock();Journal.event(context,details);if(failed!=null)failed.run(this,1,code);}
    void release(){
        if(released)return;released=true;main.removeCallbacksAndMessages(null);unlock();
        NetworkAudio input=network;if(input!=null)input.cancel();
        worker.execute(()->{if(stream!=0){BASS.BASS_StreamFree(stream);stream=0;}if(network!=null)network.close();if(descriptor!=null)try{descriptor.close();}catch(IOException ignored){}});
        worker.shutdown();
    }
}
