package car.salt.player;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import java.util.*;
import java.io.*;
import org.json.*;
public class SystemMergeProbe extends Instrumentation {
    Home h; boolean restore,preview; String cachePhase=""; boolean audit,uiAudit,scopeBefore;
    StringBuilder log=new StringBuilder();
    public void onCreate(Bundle b){super.onCreate(b);scopeBefore=b!=null&&"true".equals(b.getString("scopeBefore"));uiAudit=b!=null&&"true".equals(b.getString("uiAudit"));audit=b!=null&&"true".equals(b.getString("audit"));cachePhase=b==null?"":b.getString("cachePhase","");restore=b!=null&&"true".equals(b.getString("restore"));preview=b!=null&&"true".equals(b.getString("preview"));start();}
    void check(boolean ok,String name){if(!ok)throw new AssertionError(name);log.append(name).append("\n");}
    void waitMain(java.util.concurrent.Callable<Boolean> test,String name)throws Exception{
        for(int i=0;i<90;i++){boolean[] yes={false};runOnMainSync(()->{try{yes[0]=test.call();}catch(Exception e){throw new RuntimeException(e);}});if(yes[0]){check(true,name);return;}Thread.sleep(250);}throw new AssertionError(name);}
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
    void auditChecks(Context c)throws Exception{
        File leftover=new File(c.getFilesDir(),"server-catalog-v2");
        if(leftover.isDirectory()&&new File(leftover,"blocked").isFile()){new File(leftover,"blocked").delete();leftover.delete();new File(c.getFilesDir(),"server-catalog-v2.bak").delete();}
        Server server=new Server("http://127.0.0.1:18053","demo","demo");server.save(c);
        ArrayList<Track> partial=new ArrayList<>();partial.add(new Track(new JSONObject().put("id","audit-partial").put("title","Audit partial").put("server",server.account())));
        CatalogStore.save(c,server.account(),partial,false);
        runOnMainSync(()->h.finish());
        h=(Home)startActivitySync(new Intent(c,Home.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitMain(()->h.deck!=null&&h.deck.restored&&h.libraryReady,"audit activity restarted");
        int requests=h.request;runOnMainSync(()->h.go("服务器"));Thread.sleep(400);
        check(h.catalogLoaded&&!h.catalogComplete&&h.request==requests&&h.serverNote.contains("尚未"),"partial catalog restored with completion notice");

        File invalid=new File(c.getFilesDir(),"audit-invalid");try(FileOutputStream out=new FileOutputStream(invalid)){out.write("broken-json".getBytes("UTF-8"));}
        boolean refused=false;try{Library.load(c,"audit-invalid");}catch(IOException expected){refused=true;}check(refused,"corrupt library reported instead of normal empty data");
        refused=false;try{Library.save(c,"audit-invalid",new JSONArray());}catch(IOException expected){refused=true;}check(refused,"unrecoverable corruption not overwritten");invalid.delete();
        Library.save(c,"audit-recovery",new JSONArray().put("valid"));try(FileOutputStream out=new FileOutputStream(new File(c.getFilesDir(),"audit-recovery"))){out.write("broken".getBytes());}
        check(Library.load(c,"audit-recovery").getString(0).equals("valid"),"last good snapshot restores damaged file");

        File cache=new File(c.getFilesDir(),"server-catalog-v2");cache.delete();check(cache.mkdir(),"simulate catalog write failure");new File(cache,"blocked").createNewFile();File backup=new File(c.getFilesDir(),"server-catalog-v2.bak");backup.mkdir();
        runOnMainSync(()->{h.remote.clear();h.loadAlbums(0);});waitMain(()->!h.remoteBusy,"refresh with unavailable cache storage finishes");
        check(h.remote.size()==1&&h.pendingCatalog!=null,"failed save retains explicit pending snapshot");
        check(h.serverNote.contains("保存失败"),"cache write failure visible on page");new File(cache,"blocked").delete();cache.delete();backup.delete();runOnMainSync(()->h.retryCatalogSave());waitMain(()->!h.remoteBusy&&h.pendingCatalog==null,"retry saves pending catalog");

        Uri absent=Uri.parse("content://car.salt.unifiedprobe.pipe/tree/absent");boolean rejected=false;
        try{Library.scan(c,absent,partial);}catch(Library.EmptyFolder expected){rejected=true;}
        check(rejected,"empty readable folder separated from read failure");
        Thread.currentThread().interrupt();boolean cancelled=false;try{Library.scan(c,absent,new ArrayList<Track>());}catch(InterruptedIOException expected){cancelled=true;}finally{Thread.interrupted();}check(cancelled,"interrupted scan cancels before enumeration");

        File mp3=new File(c.getFilesDir(),"audit.mp3");try(InputStream in=getContext().getAssets().open("01 Drive.mp3");OutputStream out=new FileOutputStream(mp3)){out.write(Library.bytes(in,1024*1024));}
        Track a=new Track(new JSONObject().put("id",Uri.fromFile(mp3).toString()).put("title","A"));
        Track b=new Track(new JSONObject().put("id",Uri.fromFile(mp3).toString()).put("title","B"));
        Track next=new Track(new JSONObject().put("id",Uri.fromFile(mp3).toString()).put("title","Requested next"));
        runOnMainSync(()->{
            h.deck.replace(Arrays.asList(a,b),0);h.deck.mode=1;h.deck.insert(next);
            try{java.lang.reflect.Field field=Deck.class.getDeclaredField("random");field.setAccessible(true);((Random)field.get(h.deck)).setSeed(0);}catch(Exception e){throw new RuntimeException(e);}
            h.deck.step(1,false);
            check(h.deck.current()==next,"explicit next has priority over shuffle");h.deck.step(-1,false);check(h.deck.current()==a,"previous follows actual playback history");h.deck.pauseTrack();
        });
        for(String name:new String[]{"gain.ogg","gain.opus","gain.ape","gain.m4a"}){
            File fixture=new File(c.getFilesDir(),name);try(InputStream in=getContext().getAssets().open(name);OutputStream out=new FileOutputStream(fixture)){out.write(Library.bytes(in,4*1024*1024));}
            check(Math.abs(ReplayGain.db(ReplayGain.read(c,Uri.fromFile(fixture)))+6)<.01,"ReplayGain tags "+name);
        }
        String structured="{\"lyricsList\":{\"structuredLyrics\":[{\"synced\":true,\"line\":[{\"start\":2000,\"value\":\"Structured\"}]}]}}";
        check(Lyrics.parse(Lyrics.structured(new JSONObject(structured))).get(2000).equals("Structured"),"structured server lyrics parsed");
        check(Lyrics.decode("中文歌词".getBytes("GBK")).equals("中文歌词"),"GBK lyric decoding");
        check(Lyrics.parse("[offset:500]\n[00:02.00]Line").firstKey()==1500,"LRC offset applied");
        File lrc=new File(c.getFilesDir(),"audit.lrc");try(FileOutputStream out=new FileOutputStream(lrc)){out.write("[00:00.00]Old".getBytes());}
        a.data.put("lrc",Uri.fromFile(lrc).toString());runOnMainSync(()->{h.deck.replace(Arrays.asList(a),0);h.go("歌词");});waitMain(()->h.lines.containsValue("Old"),"initial local lyrics displayed");
        try(FileOutputStream out=new FileOutputStream(lrc)){out.write("[00:00.00]New".getBytes());}
        runOnMainSync(()->h.render());waitMain(()->h.lines.containsValue("New"),"edited local lyrics refreshed without audio rescan");
        final long[] elapsed={0};runOnMainSync(()->{long start=System.currentTimeMillis();try{h.deck.replace(Arrays.asList(new Track(new JSONObject().put("id","content://car.salt.unifiedprobe.pipe/slow").put("title","slow"))),0);}catch(Exception e){throw new RuntimeException(e);}elapsed[0]=System.currentTimeMillis()-start;});check(elapsed[0]<1500,"blocking source initialization stays off UI thread");
        Thread.sleep(500);runOnMainSync(()->{try{java.lang.reflect.Field field=Deck.class.getDeclaredField("prepareTimeout");field.setAccessible(true);((Runnable)field.get(h.deck)).run();}catch(Exception e){throw new RuntimeException(e);}});
        check(!h.deck.want&&h.deck.message.contains("超时"),"preparation deadline reports timeout");
        runOnMainSync(()->{h.local.clear();h.local.add(a);h.go("本地");
            android.view.View random=described(h.root,"随机播放"),sort=described(h.root,"排序");check(random.getLayoutParams().width==sort.getLayoutParams().width&&random.getLayoutParams().height==sort.getLayoutParams().height,"shuffle and sort buttons use same size");
            check(described(h.bar,"下一首")!=null&&described(h.bar,"播放队列")==null,"bottom bar replaces queue with next");
            h.deck.replace(Arrays.asList(a,b),0);h.deck.mode=0;described(h.bar,"下一首").performClick();check(h.deck.index==1,"bottom next button advances playback");h.deck.pauseTrack();
        });
    }
    void uiRecoveryChecks(Context c)throws Exception{
        Track first=h.local.isEmpty()?h.deck.current():h.local.get(0);ArrayList<Track> many=new ArrayList<>();for(int i=0;i<80;i++)many.add(new Track(new JSONObject(first.data.toString()).put("title",String.format(Locale.ROOT,"%03d",i))));
        Library.save(c,"library",Track.pack(many));
        runOnMainSync(()->{h.local.clear();h.local.addAll(many);h.category="歌曲";h.query="";h.go("本地");});waitMain(()->listView(h.content)!=null,"list ready for state persistence");
        runOnMainSync(()->listView(h.content).setSelectionFromTop(30,-8));waitMain(()->listView(h.content).getFirstVisiblePosition()>=29,"scrolled before activity recreation");
        Thread.sleep(800);runOnMainSync(()->h.finish());h=(Home)startActivitySync(new Intent(c,Home.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Thread.sleep(1000);
        waitMain(()->h.libraryReady&&listView(h.content)!=null&&listView(h.content).getFirstVisiblePosition()>=29,"list scroll restored after activity recreation");
        runOnMainSync(()->h.scan(Uri.parse("content://car.salt.unifiedprobe.pipe/tree/slowScan")));Thread.sleep(300);runOnMainSync(()->h.finish());
        Library.save(c,"library",Track.pack(many));Thread.sleep(4500);
        check(Library.load(c,"library").length()==80,"destroyed scan cannot overwrite newer library snapshot");
        h=(Home)startActivitySync(new Intent(c,Home.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitMain(()->h.libraryReady,"new activity restored after cancelled scan");
    }
    void uiScreens(Context c)throws Exception{
        File dir=new File(c.getExternalFilesDir(null),"ui-audit");dir.mkdirs();
        runOnMainSync(()->{
            try{Track current=h.deck.current();if(current==null)throw new IllegalStateException("test queue required");h.deck.pauseTrack();h.local.clear();h.remote.clear();
                for(int i=0;i<4;i++){Track t=new Track(new JSONObject(current.data.toString()).put("title",i==0?"一首名字特别长的歌曲 · Live 完整版":"测试歌曲 "+i).put("artist","演唱者甲 / 演唱者乙").put("album","专辑名称 · 现场特别收录版").put("folder",i%2==0?"流行音乐/":"经典收藏/"));h.local.add(t);h.remote.add(new Track(new JSONObject(t.data.toString()).put("server",CatalogStore.owner(c)).put("format","flac")));}
                h.category="歌曲";h.query="";h.group="";h.serverSearch="";h.catalogLoaded=true;h.catalogComplete=false;h.serverNote="曲库尚未读取完整，点击补全继续读取";
            }catch(Exception e){throw new RuntimeException(e);}
        });
        for(boolean dark:new boolean[]{false,true}){
            runOnMainSync(()->{try{java.lang.reflect.Field field=Home.class.getDeclaredField("dark");field.setAccessible(true);if(field.getBoolean(h)!=dark){java.lang.reflect.Method method=Home.class.getDeclaredMethod("toggleTheme");method.setAccessible(true);method.invoke(h);}}catch(Exception e){throw new RuntimeException(e);}});
            for(String page:new String[]{"本地","服务器","扫描音乐","设置","日志","歌词"}){
                runOnMainSync(()->{h.logsExpanded=page.equals("日志");h.go(page.equals("日志")?"设置":page);});Thread.sleep(800);
                try(OutputStream out=new FileOutputStream(new File(dir,"step.txt"))){out.write(((dark?"dark-":"light-")+page).getBytes("UTF-8"));}
                runOnMainSync(()->{android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(h.root.getWidth(),h.root.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);h.root.draw(new android.graphics.Canvas(bitmap));try(OutputStream out=new FileOutputStream(new File(dir,(dark?"dark-":"light-")+page+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}catch(IOException e){throw new RuntimeException(e);}finally{bitmap.recycle();}});

            }
        }
        runOnMainSync(()->{h.go("本地");h.query="测试歌曲 1";h.render();});Thread.sleep(500);
        int shown=songCount(h.content);runOnMainSync(()->described(h.root,"随机播放").performClick());
        log.append("Filtered rows="+shown+"; shuffle queue="+h.deck.queue.size()+"\n");
        runOnMainSync(()->h.deck.pauseTrack());
        check(shown==1&&h.deck.queue.size()==1,"UI filtered random scope preserved");
    }
    int savedQueueCount(Context c){try{return new JSONObject(new String(Store.read(c,"deck-snapshot"),"UTF-8")).getJSONArray("queue").length();}catch(Exception e){return -1;}}
    public void onStart(){Bundle result=new Bundle();try{
        Context c=getTargetContext();
        if(cachePhase.equals("seed")){
            c.getSharedPreferences("account",0).edit().putString("base","http://127.0.0.1:18053").putString("user","demo").putString("secret","invalid-test-secret").commit();
            String owner=CatalogStore.owner(c);
            ArrayList<Track> songs=new ArrayList<>();songs.add(new Track(new JSONObject().put("id","partial").put("title","Saved before completion").put("server",owner)));
            CatalogStore.save(c,owner,songs,false);
            check(CatalogStore.restore(c).size()==1,"partial catalog persisted before refresh completion");
            c.getSharedPreferences("account",0).edit().putString("user","other").commit();
            check(CatalogStore.restore(c).isEmpty(),"cache isolated from other account");
            c.getSharedPreferences("account",0).edit().putString("user","demo").commit();
        }
        h=(Home)startActivitySync(new Intent(c,Home.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitMain(()->h.deck!=null&&h.deck.restored&&h.libraryReady,"launch and service bind on API "+Build.VERSION.SDK_INT);
        if(scopeBefore){
            runOnMainSync(()->{try{Track original=h.deck.current();h.local.clear();for(int i=0;i<4;i++)h.local.add(new Track(new JSONObject(original.data.toString()).put("id","scope"+i).put("title","Folder test "+i).put("tree",i==3?"other-root":"root").put("folder",i==2?"Pop/":"Rock/")));h.category="文件夹";h.group=h.folderKey(h.local.get(0));h.query="";h.go("本地");}catch(Exception e){throw new RuntimeException(e);}});
            check(songCount(h.content)==2,"folder excludes same-name folder on another root");
            runOnMainSync(()->{android.widget.ListView list=listView(h.content);list.performItemClick(list.getChildAt(0),0,0);});check(h.deck.queue.size()==2,"click folder song creates folder-only queue");
            runOnMainSync(()->described(h.root,"随机播放").performClick());
            check(h.deck.queue.size()==2,"folder shuffle queue contains only folder tracks");
            for(int i=0;i<12;i++){runOnMainSync(()->h.deck.step(1,false));check(h.deck.current().id().equals("scope0")||h.deck.current().id().equals("scope1"),"random next remains in folder");}
            runOnMainSync(()->{h.query="test 1";h.go("本地");});check(songCount(h.content)==1,"folder search shows one match");
            runOnMainSync(()->described(h.root,"随机播放").performClick());check(h.deck.queue.size()==1&&h.deck.current().id().equals("scope1"),"folder filtered shuffle has one match");
            runOnMainSync(()->{h.category="歌曲";h.query="test 2";h.go("本地");described(h.root,"随机播放").performClick();});check(h.deck.queue.size()==1&&h.deck.current().id().equals("scope2"),"local filtered shuffle scoped");
            runOnMainSync(()->{h.query="no matches";h.go("本地");});check(!described(h.root,"随机播放").isEnabled(),"empty search disables shuffle");
            runOnMainSync(()->{h.query="";h.group="";h.remote.clear();h.remote.addAll(h.local);h.catalogLoaded=true;h.serverSearch="test 1";h.go("服务器");described(h.root,"随机播放").performClick();});check(h.deck.queue.size()==1&&h.deck.current().id().equals("scope1"),"server filtered shuffle scoped");
            runOnMainSync(()->{h.serverSearch="";h.go("服务器");described(h.root,"多选歌曲").performClick();h.chosen.add(h.trackKey(h.remote.get(2)));h.render();described(h.root,"随机播放").performClick();});check(h.deck.queue.size()==1&&h.deck.current().id().equals("scope2"),"server selected shuffle scoped");
            runOnMainSync(()->{h.deck.pauseTrack();h.category="歌曲";h.group="";h.query="";h.serverSearch="";h.go("本地");});result.putString("result",log.toString());finish(Activity.RESULT_OK,result);return;
        }
        if(uiAudit){uiScreens(c);result.putString("result",log.toString());finish(Activity.RESULT_OK,result);return;}
        if(audit){auditChecks(c);uiRecoveryChecks(c);result.putString("result",log.toString());finish(Activity.RESULT_OK,result);return;}
        if(cachePhase.equals("fetch")){
            Server server=new Server("http://127.0.0.1:18053","demo","demo");server.save(c);server.call("qaMode","value","slow");CatalogStore.save(c,server.account(),new ArrayList<Track>(),false);
            runOnMainSync(()->{h.remote.clear();h.loadAlbums(0);});waitMain(()->h.remote.size()==1&&h.remoteBusy,"first batch visible while next album still loading");
            check(!CatalogStore.load(c).complete&&CatalogStore.restore(c).size()==1,"first batch durable before full fetch completes");result.putString("result",log.toString());finish(Activity.RESULT_OK,result);return;
        }
        if(cachePhase.equals("afterFetch")){
            check(h.remote.size()==1&&!h.catalogComplete&&h.serverNote.contains("尚未"),"force-stopped real fetch restored partial songs with notice");result.putString("result",log.toString());finish(Activity.RESULT_OK,result);return;
        }
        if(!cachePhase.isEmpty()){
            check(h.remote.size()==1&&h.remote.get(0).id().equals("partial"),"partial catalog restored without decrypting credentials");
            int count=h.request;runOnMainSync(()->h.go("服务器"));Thread.sleep(600);
            check(h.request==count&&!h.remoteBusy,"offline reopen does not refetch cached catalog");
            result.putString("result",log.toString());finish(Activity.RESULT_OK,result);return;
        }
        if(preview){
            runOnMainSync(()->{h.deck.resumeTrack();h.go("歌词");});Thread.sleep(15000);
            result.putString("result","player preview captured");finish(Activity.RESULT_OK,result);return;
        }
        if(restore){
            check(h.local.size()==3,"library restored without scan");check(h.remote.size()==1,"server catalog restored across process restart");
            check(h.deck.queue.size()==3&&!h.deck.want,"queue restored without auto play");
            check(h.deck.mark>=4000,"position restored");
            runOnMainSync(()->h.deck.resumeTrack());waitMain(()->h.deck.playing(),"manual playback after process restart");
            runOnMainSync(()->{h.deck.seek(4000);h.deck.pauseTrack();});
        result.putString("result",log.toString());finish(Activity.RESULT_OK,result);return;
        }
        runOnMainSync(()->{h.category="歌曲";h.group="";h.query="";h.serverSearch="";});
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
        runOnMainSync(()->{h.deck.prefs().edit().putBoolean("replayGain",false).apply();h.deck.refreshGain();});
        waitMain(()->h.deck.appliedGainDb==0&&h.deck.playing(),"disable ReplayGain restores original gain without interrupting playback");
        ArrayList<Track> files=new ArrayList<>();
        for(String name:new String[]{"01 Drive.mp3","02 Coast.flac","03 Waves.wav","01 Drive.lrc"}){
            File copy=new File(c.getFilesDir(),name);try(InputStream in=getContext().getAssets().open(name);OutputStream out=new FileOutputStream(copy)){byte[] buf=new byte[16384];int n;while((n=in.read(buf))>0)out.write(buf,0,n);}
            if(name.endsWith("lrc"))continue;
            JSONObject d=new JSONObject().put("id",Uri.fromFile(copy).toString()).put("title",name);
            if(name.endsWith("mp3"))d.put("lrc",Uri.fromFile(new File(c.getFilesDir(),"01 Drive.lrc")).toString());files.add(new Track(d));
        }
        for(int i=0;i<3;i++){final int pos=i;runOnMainSync(()->h.deck.replace(files,pos));waitMain(()->h.deck.playing()&&h.deck.position()>100,"decode "+files.get(i).title());}
        check(files.get(0).data.optInt("duration")>0,"prepared duration saved to track");
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
            int upper=left.getChildAt(0).getTop(),lower=left.getHeight()-left.getChildAt(left.getChildCount()-1).getBottom();
            check(Math.abs(upper-lower)<=2,"left content vertically centered with balanced outer spacing");
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
        Server server=new Server("http://127.0.0.1:18053","demo","demo");server.call("ping");server.save(c);
        check(Server.load(c).account().equals(server.account()),"keystore credentials round trip");
        runOnMainSync(()->{h.remote.clear();h.catalogLoaded=false;h.go("服务器");});waitMain(()->!h.remoteBusy&&h.remote.size()==1&&h.serverNote.contains("1 张专辑"),"independent server songs page");
        waitMain(()->showsCover(h.content),"server list displays correct cover pixels");
        server.call("qaMode","value","fail");
        runOnMainSync(()->h.loadAlbums(0));waitMain(()->!h.remoteBusy,"failed refresh finishes");
        check(h.remote.size()==1,"failed refresh preserves catalog");check(CatalogStore.restore(c).size()==1,"failed refresh preserves disk catalog");
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
        waitMain(()->savedQueueCount(c)==4,"queue persisted atomically");
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
        // Native APE PCM equality is covered by the software-engine Probe.
        Track apeTrack=new Track(new JSONObject().put("id",Uri.fromFile(ape).toString()).put("title","APE regression"));
        runOnMainSync(()->h.deck.replace(java.util.Arrays.asList(apeTrack),0));
        waitMain(()->h.deck.playing()&&h.deck.position()>100,"APE playback through software decoder");
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


