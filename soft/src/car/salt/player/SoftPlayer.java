package car.salt.soft;

import android.content.Context;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.util.*;
import org.videolan.libvlc.*;
import org.videolan.libvlc.interfaces.IMedia;

/** Strict software-only audio path. Android is used only for PCM output. */
final class SoftPlayer {
    static final String DECODERS="avcodec,araw,flac,vorbis,opus,mpg123,none";
    interface Prepared {void run(SoftPlayer p);}
    interface Complete {void run(SoftPlayer p);}
    interface Failed {boolean run(SoftPlayer p,int what,int extra);}
    private static LibVLC engine;
    private final Context context;
    private final org.videolan.libvlc.MediaPlayer player;
    private Media media;
    private ParcelFileDescriptor descriptor;
    private Prepared prepared;
    private Complete complete;
    private Failed failed;
    private boolean released,notified,active;
    private double gain; private org.videolan.libvlc.MediaPlayer.Equalizer gainEq;
    private PowerManager.WakeLock wake;
    private final Handler timer=new Handler(Looper.getMainLooper());
    private final Runnable timeout=()->{if(!released&&!notified)error("软解打开或缓冲超时",-110);};
    private String reason="软解失败，尚未确定原因";
    private boolean loggedTracks,loggedDecoded;
    SoftPlayer(Context context){
        this.context=context.getApplicationContext();
        if(engine==null)engine=new LibVLC(this.context,new ArrayList<>(Arrays.asList("--no-video","--no-spu","--no-metadata-network-access","--no-audio-time-stretch","--aout=android_audiotrack","--codec="+DECODERS,"--network-caching=1500","--file-caching=400","--quiet")));
        player=new org.videolan.libvlc.MediaPlayer(engine);
        player.setAudioDigitalOutputEnabled(false);
        player.setEventListener(event->{
            if(released)return;
            switch(event.type){
                case org.videolan.libvlc.MediaPlayer.Event.Playing:
                    active=true;
                    if(!notified){notified=true;timer.removeCallbacks(timeout);if(prepared!=null)prepared.run(this);}
                    logTracks();break;
                case org.videolan.libvlc.MediaPlayer.Event.TimeChanged:logTracks();break;
                case org.videolan.libvlc.MediaPlayer.Event.Paused:active=false;break;
                case org.videolan.libvlc.MediaPlayer.Event.EndReached:active=false;unlock();if(complete!=null)complete.run(this);break;
                case org.videolan.libvlc.MediaPlayer.Event.EncounteredError:
                    error(failureDetails(),-1);break;
            }
        });
        Journal.event(context,"引擎=LibVLC "+LibVLC.version()+"；系统解码与直通禁用；软件模块="+DECODERS);
    }
    void setAudioStreamType(int stream){}
    void setWakeMode(Context c,int mode){wake=((PowerManager)c.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"NewSalt:SoftDecode");wake.setReferenceCounted(false);}
    void setOnPreparedListener(Prepared listener){prepared=listener;}
    void setOnCompletionListener(Complete listener){complete=listener;}
    void setOnErrorListener(Failed listener){failed=listener;}
    void setDataSource(String address)throws Exception{setDataSource(context,address.contains("://")?Uri.parse(address):Uri.fromFile(new File(address)));}
    void setDataSource(Context c,Uri uri)throws Exception{
        if(media!=null)throw new IllegalStateException("data source already assigned");
        if("http".equals(uri.getScheme())||"https".equals(uri.getScheme()))media=new Media(engine,uri);
        else{
            ParcelFileDescriptor opened=c.getContentResolver().openFileDescriptor(uri,"r");
            if(opened==null)throw new FileNotFoundException();
            try{android.system.Os.lseek(opened.getFileDescriptor(),0,android.system.OsConstants.SEEK_CUR);media=new Media(engine,opened.getFileDescriptor());descriptor=opened;}
            catch(Exception ex){opened.close();throw new IOException("文件流不可定位，需要兼容复制",ex);}
            Journal.event(context,"软解文件已打开 scheme="+uri.getScheme()+"；文件可定位");
        }
        media.setHWDecoderEnabled(false,false);
        media.addOption(":codec="+DECODERS);
        media.addOption(":no-video");media.addOption(":no-spu");
        player.setMedia(media);
    }
    void prepareAsync(){
        if(media==null)throw new IllegalStateException("missing source");
        player.setVolume(0);lock();timer.postDelayed(timeout,30000);player.play();
    }
    void start(){if(released)return;lock();applyGain();active=true;player.play();}
    void pause(){if(released)return;active=false;if(player.isPlaying())player.pause();unlock();}
    void seekTo(int position){if(!released)player.setTime(position,false);}
    boolean isPlaying(){return !released&&active&&player.isPlaying();}
    int getCurrentPosition(){return released?0:(int)Math.max(0,Math.min(Integer.MAX_VALUE,player.getTime()));}
    int getDuration(){return released?0:(int)Math.max(0,Math.min(Integer.MAX_VALUE,player.getLength()));}
    void setGain(double db){gain=db;if(notified)applyGain();}
    private void applyGain(){
        if(gain<=0){player.setEqualizer(null);player.setVolume((int)Math.round(100*Math.pow(10,gain/20)));}
        else{if(gainEq==null)gainEq=org.videolan.libvlc.MediaPlayer.Equalizer.create();gainEq.setPreAmp((float)Math.min(20,gain));if(!player.setEqualizer(gainEq))throw new IllegalStateException("PCM gain unavailable");player.setVolume(100);}
    }
    private void lock(){if(wake!=null&&!wake.isHeld())wake.acquire();}
    private void unlock(){if(wake!=null&&wake.isHeld())wake.release();}
    private void logTracks(){
        if(media==null)return;
        int frames=decodedFrames();if(!loggedDecoded&&frames>0){loggedDecoded=true;Journal.event(context,"实际软件解码输出：音频块="+frames+"；允许解码器="+DECODERS);}
        if(loggedTracks)return;
        for(int i=0;i<media.getTrackCount();i++){IMedia.Track track=media.getTrack(i);if(track instanceof IMedia.AudioTrack){IMedia.AudioTrack a=(IMedia.AudioTrack)track;Journal.event(context,"软件解码音轨 codec="+a.codec+"，采样率="+a.rate+"，声道="+a.channels+"；输出=PCM/AudioTrack");loggedTracks=true;}}
    }
    int decodedFrames(){IMedia.Stats stats=media==null?null:media.getStats();return stats==null?0:stats.decodedAudio;}
    String failureReason(){return reason;}
    private String failureDetails(){
        IMedia.Stats stats=media==null?null:media.getStats();
        if(stats==null||stats.readBytes==0)return "软解输入读取失败：文件或网络访问、容器识别尚未确定";
        if(stats.decodedAudio==0)return "软解已读取数据但未输出音频：编码、容器或数据异常，尚未确定";
        return "软解已输出音频后异常：后续数据或音频输出问题";
    }
    private void error(String details,int code){reason=details;timer.removeCallbacks(timeout);unlock();Journal.event(context,"软解失败阶段："+details+"；已解码音频块="+decodedFrames());if(failed!=null)failed.run(this,1,code);}
    void release(){if(released)return;released=true;timer.removeCallbacksAndMessages(null);unlock();player.setEventListener(null);player.stop();player.release();if(media!=null){media.release();media=null;}if(descriptor!=null)try{descriptor.close();}catch(IOException ignored){}}
}
