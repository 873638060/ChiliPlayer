package car.salt.player;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import java.util.*;
import java.io.*;
import org.json.*;
public class Probe extends Instrumentation {
    Home h; boolean restore,preview,extra;
    StringBuilder log=new StringBuilder();
    public void onCreate(Bundle b){super.onCreate(b);restore=b!=null&&"true".equals(b.getString("restore"));preview=b!=null&&"true".equals(b.getString("preview"));extra=b!=null&&"true".equals(b.getString("extra"));start();}
    void check(boolean ok,String name){if(!ok)throw new AssertionError(name);log.append(name).append("\n");}
    void waitMain(java.util.concurrent.Callable<Boolean> test,String name)throws Exception{
        for(int i=0;i<90;i++){boolean[] yes={false};runOnMainSync(()->{try{yes[0]=test.call();}catch(Exception e){throw new RuntimeException(e);}});if(yes[0]){check(true,name);return;}Thread.sleep(250);}throw new AssertionError(name+" note="+(h==null?"":h.serverNote)+" busy="+(h==null?false:h.remoteBusy)+" songs="+(h==null?0:h.remote.size()));}
    boolean showsCover(android.view.View view){
        if(view instanceof android.widget.ImageView){
            android.graphics.drawable.Drawable d=((android.widget.ImageView)view).getDrawable();
            if(d instanceof android.graphics.drawable.BitmapDrawable)return ((android.graphics.drawable.BitmapDrawable)d).getBitmap().getPixel(0,0)==0xff168653;
        }
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)view;for(int i=0;i<g.getChildCount();i++)if(showsCover(g.getChildAt(i)))return true;}
        return false;
    }
    int songCount(android.view.View v){
        if(v instanceof android.widget.ListView)return ((android.widget.ListView)v).getCount();
        if(v instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)v;for(int i=0;i<g.getChildCount();i++){int count=songCount(g.getChildAt(i));if(count>=0)return count;}}
        return -1;
    }
    android.widget.ListView listView(android.view.View view){
        if(view instanceof android.widget.ListView)return (android.widget.ListView)view;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){android.widget.ListView list=listView(group.getChildAt(i));if(list!=null)return list;}}return null;
    }
    void cacheChecks(Context c)throws Exception{
        String key="probe|"+System.nanoTime();java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{java.util.concurrent.Callable<byte[]> task=()->BlobCache.get(c,key,100,()->{reads.incrementAndGet();Thread.sleep(100);return new byte[]{7};});
            java.util.concurrent.Future<byte[]> a=pool.submit(task),b=pool.submit(task);check(a.get()[0]==7&&b.get()[0]==7&&reads.get()==1,"duplicate media requests merged");
            check(BlobCache.get(c,key,100,()->{throw new IOException("offline");})[0]==7,"disk cache works offline");
            check(BlobCache.get(c,key+"another-account",100,()->new byte[]{9})[0]==9,"cache isolates account keys");
            try{BlobCache.get(c,key+"retry",100,()->{throw new IOException("offline");});}catch(IOException expected){}
            check(BlobCache.get(c,key+"retry",100,()->new byte[]{3})[0]==3,"failed cache fetch can retry");
        }finally{pool.shutdownNow();}
        File dir=new File(c.getCacheDir(),"probe-trim");dir.mkdirs();for(int i=0;i<514;i++)new File(dir,String.format(java.util.Locale.ROOT,"%064x",i)).createNewFile();BlobCache.trim(dir);check(dir.listFiles().length<=512,"cache entry bound enforced");for(File file:dir.listFiles())file.delete();dir.delete();
    }
    android.view.View described(android.view.View v,String name){if(name.equals(v.getContentDescription()))return v;if(v instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)v;for(int i=0;i<group.getChildCount();i++){android.view.View found=described(group.getChildAt(i),name);if(found!=null)return found;}}return null;}
    void indexTouch(android.view.View view,int action,float fraction){long now=android.os.SystemClock.uptimeMillis();android.view.MotionEvent e=android.view.MotionEvent.obtain(now,now,action,view.getWidth()/2f,view.getHeight()*fraction,0);view.dispatchTouchEvent(e);e.recycle();}
    float[] gainSamples(File file,float gain)throws Exception{
        int handle=com.un4seen.bass.BASS.BASS_StreamCreateFile(file.getAbsolutePath(),0,0,com.un4seen.bass.BASS.BASS_STREAM_DECODE|com.un4seen.bass.BASS.BASS_SAMPLE_FLOAT);
        check(handle!=0,"gain PCM stream opens");
        try{
            check(com.un4seen.bass.BASS.BASS_ChannelSetAttribute(handle,com.un4seen.bass.BASS.BASS_ATTRIB_VOLDSP,gain),"PCM gain attribute accepted");
            java.nio.ByteBuffer data=java.nio.ByteBuffer.allocateDirect(4096).order(java.nio.ByteOrder.nativeOrder());
            int n=com.un4seen.bass.BASS.BASS_ChannelGetData(handle,data,4096);check(n>0,"gain test decoded PCM");
            float[] samples=new float[n/4];data.position(0);data.asFloatBuffer().get(samples);return samples;
        }finally{com.un4seen.bass.BASS.BASS_StreamFree(handle);}
    }
    int savedQueueCount(Context c){try{return new JSONObject(new String(Store.read(c,"deck-snapshot"),"UTF-8")).getJSONArray("queue").length();}catch(Exception e){return -1;}}
    public void onStart(){Bundle result=new Bundle();try{
        Context c=getTargetContext();
        h=(Home)startActivitySync(new Intent(c,Home.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitMain(()->h.deck!=null&&h.deck.restored&&h.libraryReady,"launch and service bind on API "+Build.VERSION.SDK_INT);
        runOnMainSync(()->{h.query="";h.group="";h.category="歌曲";h.serverSearch="";h.selecting=false;h.chosen.clear();h.go("本地");});
        if(extra){
            Track waveTrack=new Track(new JSONObject().put("id",Uri.fromFile(new File(c.getFilesDir(),"03 Waves.wav")).toString()).put("title","Native seek check"));
            runOnMainSync(()->h.deck.replace(Arrays.asList(waveTrack),0));waitMain(()->h.deck.playing(),"seek check playing");
            runOnMainSync(()->{h.deck.seek(6000);h.deck.pauseTrack();});
            waitMain(()->{
                java.lang.reflect.Field playerField=Deck.class.getDeclaredField("audio");playerField.setAccessible(true);Object player=playerField.get(h.deck);
                java.lang.reflect.Field nativeField=SoftPlayer.class.getDeclaredField("stream");nativeField.setAccessible(true);int handle=nativeField.getInt(player);
                long pos=com.un4seen.bass.BASS.BASS_ChannelGetPosition(handle,com.un4seen.bass.BASS.BASS_POS_BYTE);
                return com.un4seen.bass.BASS.BASS_ChannelBytes2Seconds(handle,pos)>=5.9;
            },"actual native playback position seeks while paused");
            check(h.deck.mark>=5900,"immediate pause retains requested position");
            NetworkAudio net=new NetworkAudio(c,Server.load(c).stream(h.remote.isEmpty()?new Track(new JSONObject().put("id","s1").put("server",Server.load(c).account())):h.remote.get(0)));
            try{
                check(net.FILELENPROC(null)>0,"network length available");
                java.nio.ByteBuffer bytes=java.nio.ByteBuffer.allocateDirect(131072);
                check(net.FILEREADPROC(bytes,131072,null)==131072,"network callback fills requests larger than 64 KiB");
                check(net.FILESEEKPROC(0,null),"network cache seek");bytes.clear();check(net.FILEREADPROC(bytes,4,null)==4,"network cache reread");bytes.flip();check(bytes.get()=='R'&&bytes.get()=='I'&&bytes.get()=='F'&&bytes.get()=='F',"network cache preserves exact WAV header");
                net.cancel();check(net.FILEREADPROC(bytes,4,null)==0,"network cancellation stops reads");
            }finally{net.close();}
            runOnMainSync(()->h.deck.release());result.putString("result",log.toString());finish(Activity.RESULT_OK,result);return;
        }
        if(preview){
            runOnMainSync(()->h.go("本地"));Thread.sleep(700);
            runOnMainSync(()->indexTouch(described(h.root,"歌曲首字母索引"),android.view.MotionEvent.ACTION_DOWN,3.5f/28f));Thread.sleep(700);
            Thread.sleep(12000);
            result.putString("result","index preview captured");finish(Activity.RESULT_OK,result);return;
        }
        if(restore){
            check(h.local.size()==3,"library restored without scan");
            check(h.deck.queue.size()==3&&!h.deck.want,"queue restored without auto play");
            check(h.deck.mark>=4000,"position restored");
            runOnMainSync(()->h.deck.resumeTrack());waitMain(()->h.deck.playing(),"manual playback after process restart");
            runOnMainSync(()->{h.deck.seek(4000);h.deck.pauseTrack();});
        java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);String[] snapshot={""};
        Journal.read(c,(text,ok)->{snapshot[0]=ok?text:"";done.countDown();});done.await(10,java.util.concurrent.TimeUnit.SECONDS);
        check(snapshot[0].contains("实际软件解码输出")&&snapshot[0].contains("引擎=BASS"),"exported journal contains software decoding evidence");
        check(snapshot[0].contains((android.os.Process.is64Bit()?"ARM64":"ARM32")+" 音频；"),"journal reports actual process architecture");
        try(OutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"soft-audit.txt"))){out.write(snapshot[0].getBytes("UTF-8"));}
        result.putString("result",log.toString());finish(Activity.RESULT_OK,result);return;
        }
        check(Home.initial("周杰伦").equals("Z")&&Home.initial("陈奕迅").equals("C")&&Home.initial("Drive").equals("D"),"Chinese and Latin list index");
        check(PlaybackFailure.player(1,-1010,false).startsWith("格式限制"),"unsupported format classified");
        check(PlaybackFailure.player(1,-1007,false).startsWith("媒体数据异常"),"malformed media classified");
        check(PlaybackFailure.player(1,0,false).contains("未明确"),"unknown error is not mislabelled format failure");
        check(PlaybackFailure.exception(new IOException(new SecurityException()),"打开数据源").contains("权限"),"wrapped permission error classified");
        check(PlaybackFailure.exception(new FileNotFoundException(),"兼容文件复制").contains("文件无法打开"),"missing file classified");
        cacheChecks(c);
        check(Home.initial("123").equals("0")&&Home.INDEX.startsWith("0A"),"one numeric index group");
        check(Home.indexRank("9 song")<Home.indexRank("Alpha")&&Home.indexRank("Zebra")<Home.indexRank("#unknown"),"digits Latin others order");
        check(ReplayGain.db(new JSONObject())==0,"untagged gain is neutral");
        check(Math.abs(ReplayGain.db(new JSONObject().put("trackGain",6).put("trackPeak",2))+6.0206)<0.001,"gain peak clipping protection");
        for(String name:new String[]{"gain.mp3","gain.flac"}){
            JSONObject tags;try(InputStream in=getContext().getAssets().open(name)){tags=ReplayGain.read(in);}
            check(ReplayGain.db(tags)==(name.endsWith("mp3")?-6:-4),"read ReplayGain "+name);
        }
        File gainFile=new File(c.getFilesDir(),"gain.mp3");try(InputStream in=getContext().getAssets().open("gain.mp3");OutputStream out=new FileOutputStream(gainFile)){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}
        Track gainTrack=new Track(new JSONObject().put("id",Uri.fromFile(gainFile).toString()).put("title","Gain test"));
        runOnMainSync(()->{h.deck.prefs().edit().putBoolean("replayGain",true).apply();h.deck.replace(Arrays.asList(gainTrack),0);});
        waitMain(()->h.deck.playing()&&h.deck.appliedGainDb==-6,"local ReplayGain applied during playback");
        runOnMainSync(()->{try{java.lang.reflect.Field field=Deck.class.getDeclaredField("audio");field.setAccessible(true);SoftPlayer nativePlayer=(SoftPlayer)field.get(h.deck);nativePlayer.setGain(3);check(h.deck.playing(),"positive native PCM gain accepted");nativePlayer.setGain(-6);}catch(Exception ex){throw new RuntimeException(ex);}});
        runOnMainSync(()->{h.deck.prefs().edit().putBoolean("replayGain",false).apply();h.deck.refreshGain();});
        waitMain(()->h.deck.appliedGainDb==0&&h.deck.playing(),"disable ReplayGain restores original gain without interrupting playback");
        ArrayList<Track> files=new ArrayList<>();
        for(String name:new String[]{"01 Drive.mp3","02 Coast.flac","03 Waves.wav","01 Drive.lrc"}){
            File copy=new File(c.getFilesDir(),name);try(InputStream in=getContext().getAssets().open(name);OutputStream out=new FileOutputStream(copy)){byte[] buf=new byte[16384];int n;while((n=in.read(buf))>0)out.write(buf,0,n);}
            if(name.endsWith("lrc"))continue;
            JSONObject d=new JSONObject().put("id",Uri.fromFile(copy).toString()).put("title",name);
            if(name.endsWith("mp3"))d.put("lrc",Uri.fromFile(new File(c.getFilesDir(),"01 Drive.lrc")).toString());files.add(new Track(d));
        }
        for(int i=0;i<3;i++){final int pos=i;runOnMainSync(()->h.deck.replace(files,pos));waitMain(()->h.deck.playing()&&h.deck.position()>100,"decode "+files.get(i).title());waitMain(()->h.deck.softwareFrames()>0,"native software decoded frames "+files.get(i).title());}
        check(files.get(0).data.optInt("duration")>0,"prepared duration saved to track");
        File wave=new File(c.getFilesDir(),"03 Waves.wav");float[] neutral=gainSamples(wave,1),quiet=gainSamples(wave,0.5f),loud=gainSamples(wave,2);
        check(neutral.length==quiet.length&&neutral.length==loud.length,"PCM gain lengths match");
        double energy=0;for(int i=0;i<neutral.length;i++){energy+=neutral[i]*neutral[i];if(Math.abs(quiet[i]-neutral[i]*0.5f)>=0.00001||Math.abs(loud[i]-neutral[i]*2)>=0.00001)throw new AssertionError("PCM amplitude scales");}
        check(energy>0,"positive and negative gain verified on non-silent PCM");
        for(String name:new String[]{"Soft-AAC.m4a","Soft-ALAC.m4a","Soft-Vorbis.ogg","Soft-Opus.opus"}){
            File f=new File(c.getFilesDir(),name);try(InputStream in=getContext().getAssets().open(name);OutputStream out=new FileOutputStream(f)){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}
            Track t=new Track(new JSONObject().put("id",Uri.fromFile(f).toString()).put("title",name));
            runOnMainSync(()->h.deck.replace(Arrays.asList(t),0));waitMain(()->h.deck.playing()&&h.deck.softwareFrames()>0,"software decode "+name);
            runOnMainSync(()->h.deck.seek(6000));waitMain(()->h.deck.position()>=5500,"seek "+name);
        }
        runOnMainSync(()->h.deck.replace(files,0));
        runOnMainSync(()->{h.deck.select(0);h.go("歌词");});waitMain(()->!h.lines.isEmpty(),"timed local lyrics");
        waitMain(()->h.art!=null&&h.lyricScroll!=null&&h.activeLyric!=null,"lyrics in right scrolling pane");
        runOnMainSync(()->{
            android.widget.ImageView before=h.art;android.widget.TextView lyrics=h.lyricsText;
            android.view.ViewGroup left=(android.view.ViewGroup)((android.view.ViewGroup)h.root.getChildAt(1)).getChildAt(0);
            android.view.ViewGroup scroll=(android.view.ViewGroup)left.getChildAt(1);
            android.view.ViewGroup info=(android.view.ViewGroup)scroll.getChildAt(0);
            android.view.ViewGroup controls=(android.view.ViewGroup)info.getChildAt(info.getChildCount()-1);
            controls.getChildAt(0).performClick();check(h.art==before&&h.lyricsText==lyrics,"mode switch preserves artwork and lyrics views");
            check(((android.view.ViewGroup)left.getChildAt(0)).getChildCount()==2,"cover and song metadata share horizontal row");
        });
        Uri first=Uri.parse("content://car.salt.unifiedprobe.pipe/tree/first"),second=Uri.parse("content://car.salt.unifiedprobe.pipe/tree/second");
        runOnMainSync(()->{h.go("扫描音乐");h.scan(first);});waitMain(()->!h.scanning&&h.folders().contains(first.toString()),"add first folder");
        runOnMainSync(()->h.scan(second));waitMain(()->!h.scanning&&h.folders().contains(second.toString()),"add second folder");
        runOnMainSync(()->h.removeFolder(first.toString()));waitMain(()->!h.folders().contains(first.toString())&&h.folders().contains(second.toString()),"remove one folder");
        File external=new File(c.getExternalFilesDir(null),"storage-fallback.mp3");
        try(InputStream input=getContext().getAssets().open("01 Drive.mp3");OutputStream output=new FileOutputStream(external)){output.write(Library.bytes(input,1024*1024));}
        String root=android.os.Environment.getExternalStorageDirectory().getCanonicalPath();
        String relative=external.getCanonicalPath().substring(root.length()+1);
        Uri document=android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:"+relative);
        check(Library.bytes(SourceAccess.open(c,document),1024*1024).length==external.length(),"storage fallback opens exact document path without tree grant");
        android.media.MediaMetadataRetriever metadata=new android.media.MediaMetadataRetriever();
        try{SourceAccess.metadata(c,metadata,document);check(metadata.extractMetadata(9)!=null,"local metadata uses compatible storage path");}finally{metadata.release();}
        Track externalTrack=new Track(new JSONObject().put("id",document.toString()).put("title","Storage regression"));
        runOnMainSync(()->h.deck.replace(java.util.Arrays.asList(externalTrack),0));
        waitMain(()->h.deck.playing(),"external document playback with path fallback");
        runOnMainSync(()->h.deck.replace(files,0));
        Server server=new Server("http://127.0.0.1:18053","demo","demo");server.call("qaMode","value","normal");server.call("ping");server.save(c);
        check(Server.load(c).account().equals(server.account()),"keystore credentials round trip");
        runOnMainSync(()->{h.remote.clear();h.serverSearch="";h.catalogLoaded=false;h.go("服务器");});waitMain(()->!h.remoteBusy&&h.remote.size()==1&&h.serverNote.contains("1 张专辑"),"independent server songs page");
        waitMain(()->showsCover(h.content),"server list displays correct cover pixels");
        server.call("qaMode","value","fail");
        runOnMainSync(()->h.loadAlbums(0));waitMain(()->!h.remoteBusy,"failed refresh finishes");
        check(h.remote.size()==1,"failed refresh preserves catalog");
        server.call("qaMode","value","legacy");
        runOnMainSync(()->h.loadAlbums(0));waitMain(()->!h.remoteBusy,"legacy API fallback finishes");
        check(h.remote.size()==1&&h.serverNote.contains("1 张专辑"),"legacy album API fallback preserves songs");
        server.call("qaMode","value","normal");
        int requests=h.request;
        runOnMainSync(()->{h.go("本地");h.go("服务器");});
        check(h.request==requests&&!h.remoteBusy,"return to partial server catalog without reload");
        runOnMainSync(()->{h.serverSearch="unknown-song";h.render();});
        waitMain(()->songCount(h.content)==0,"search preserves catalog");
        runOnMainSync(()->{h.serverSearch="";h.render();});
        runOnMainSync(()->{h.deck.mode=0;h.deck.insert(h.remote.get(0));h.deck.step(1,false);});
        waitMain(()->h.deck.current().remote()&&h.deck.playing()&&h.deck.position()>200,"local to authenticated HTTP stream in shared queue");
        waitMain(()->showsCover(h.miniCover),"mini player displays correct remote cover pixels");
        runOnMainSync(()->{h.deck.prefs().edit().putBoolean("replayGain",true).apply();h.deck.refreshGain();});
        waitMain(()->h.deck.appliedGainDb==-5&&h.deck.playing(),"OpenSubsonic ReplayGain applied to stream");
        runOnMainSync(()->{h.deck.prefs().edit().putBoolean("replayGain",false).apply();h.deck.refreshGain();});

        runOnMainSync(()->h.go("播放"));
        waitMain(()->h.art!=null&&h.art.getHeight()>=h.dp(70)&&showsCover(h.art),"player album cover remains visible above controls");
        runOnMainSync(()->h.go("服务器"));

        runOnMainSync(()->h.deck.pauseTrack());waitMain(()->!h.deck.playing(),"pause");
        runOnMainSync(()->h.deck.resumeTrack());waitMain(()->h.deck.playing(),"resume");
        runOnMainSync(()->{h.deck.seek(4000);h.deck.pauseTrack();});
        waitMain(()->savedQueueCount(c)==4,"queue persisted");
        Track pipe=new Track(new JSONObject().put("id","content://car.salt.unifiedprobe.pipe/song").put("title","Pipe regression"));
        runOnMainSync(()->h.deck.replace(java.util.Arrays.asList(pipe),0));
        waitMain(()->h.deck.playing()&&h.deck.position()>100,"non seekable document pipe fallback");
        SourceAccess.writeLog(c,Uri.parse("content://car.salt.unifiedprobe.pipe/log"),"export regression".getBytes("UTF-8"));
        check(new String(Library.bytes(c.getContentResolver().openInputStream(Uri.parse("content://car.salt.unifiedprobe.pipe/log")),1024),"UTF-8").equals("export regression"),"legacy log export mode fallback and contents");
        ArrayList<Track> scanned=Library.scan(c,Uri.parse("content://car.salt.unifiedprobe.pipe/tree/root"),new ArrayList<Track>());
        check(scanned.size()==1,"document folder scan");scanned.get(0).data.put("title","Cached metadata");
        check(Library.scan(c,Uri.parse("content://car.salt.unifiedprobe.pipe/tree/root"),scanned).get(0).title().equals("Cached metadata"),"incremental scan reuses unchanged metadata");
        boolean rejected=false;try{Library.scan(c,Uri.parse("content://car.salt.unifiedprobe.pipe/tree/absent"),scanned);}catch(IOException ex){rejected=true;}
        check(rejected,"empty unmounted folder rejected without replacing library");
        File ape=new File(c.getFilesDir(),"04-Ape.ape");
        try(InputStream in=getContext().getAssets().open("04-Ape.ape");OutputStream out=new FileOutputStream(ape)){out.write(Library.bytes(in,1024*1024));}
        int raw=com.un4seen.bass.BASS.BASS_StreamCreateFile(ape.getAbsolutePath(),0,0,com.un4seen.bass.BASS.BASS_STREAM_DECODE);
        check(raw!=0,"BASS APE decode stream opens");
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        try{java.nio.ByteBuffer pcm=java.nio.ByteBuffer.allocateDirect(65536);int n;
            while((n=com.un4seen.bass.BASS.BASS_ChannelGetData(raw,pcm,65536))>0){byte[] bytes=new byte[n];pcm.position(0);pcm.get(bytes);digest.update(bytes);pcm.clear();}
        }finally{com.un4seen.bass.BASS.BASS_StreamFree(raw);}
        StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        check(hash.toString().equals("77688016a7ca738f4f2d80a2e0e07b22260fe123b0556cd19254b1a210e56908"),"BASS APE software decode PCM matches source exactly");
        Track apeTrack=new Track(new JSONObject().put("id",Uri.fromFile(ape).toString()).put("title","APE regression"));
        runOnMainSync(()->h.deck.replace(java.util.Arrays.asList(apeTrack),0));
        waitMain(()->h.deck.playing()&&h.deck.position()>100,"APE playback through software decoder");waitMain(()->h.deck.softwareFrames()>0,"native APE decoded frames");
        Track missing=new Track(new JSONObject().put("id","file:///storage/absent-volume/music.mp3").put("title","Missing"));
        runOnMainSync(()->h.deck.replace(java.util.Arrays.asList(missing),0));
        waitMain(()->!h.deck.want&&!h.deck.message.isEmpty(),"missing media reports failure");
        waitMain(()->savedQueueCount(c)==1,"missing media preserves queue");
        Library.save(c,"library",Track.pack(files));
        runOnMainSync(()->{h.local.clear();h.local.addAll(files);h.deck.replace(files,0);h.go("本地");});
        waitMain(()->h.deck.playing(),"playback recovery after missing media");
        runOnMainSync(()->{h.deck.seek(4000);h.deck.pauseTrack();});
        ArrayList<Track> many=new ArrayList<>();for(int i=0;i<80;i++)many.add(new Track(new JSONObject(files.get(0).data.toString()).put("title",String.format(java.util.Locale.ROOT,"%03d",i))));
        runOnMainSync(()->{h.local.clear();h.local.addAll(many);h.go("本地");});
        waitMain(()->listView(h.content)!=null&&listView(h.content).getCount()==80,"large list displayed");
        runOnMainSync(()->{android.view.View index=described(h.root,"歌曲首字母索引");android.widget.TextView bubble=(android.widget.TextView)described(h.root,"当前索引提示");
            indexTouch(index,android.view.MotionEvent.ACTION_DOWN,0.001f);check(bubble.getVisibility()==android.view.View.VISIBLE&&bubble.getText().toString().equals("0"),"numeric index bubble visible");
            indexTouch(index,android.view.MotionEvent.ACTION_MOVE,3.5f/28f);check(bubble.getText().toString().equals("C"),"index bubble follows drag to C");
            indexTouch(index,android.view.MotionEvent.ACTION_UP,3.5f/28f);check(bubble.getVisibility()==android.view.View.GONE,"index bubble hidden on release");
            indexTouch(index,android.view.MotionEvent.ACTION_DOWN,.99f);indexTouch(index,android.view.MotionEvent.ACTION_CANCEL,.99f);check(bubble.getVisibility()==android.view.View.GONE,"index bubble hidden on cancel");
        });
        runOnMainSync(()->listView(h.content).setSelectionFromTop(30,-8));
        waitMain(()->listView(h.content).getFirstVisiblePosition()>=29,"list scrolled");
        runOnMainSync(()->{h.go("播放");h.onBackPressed();});
        waitMain(()->listView(h.content).getFirstVisiblePosition()>=29,"return restores list position");
        runOnMainSync(()->{h.local.clear();h.local.addAll(files);h.go("本地");h.deck.release();});
        waitMain(()->h.deck.duration()>0,"duration survives player release");
        result.putString("result",log.toString());finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("result",log.toString()+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}}
}




