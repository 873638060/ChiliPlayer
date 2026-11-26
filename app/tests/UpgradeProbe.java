package car.salt.player;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** Run seed on the original APK, then verify after an in-place upgrade. */
public class UpgradeProbe extends Instrumentation {
    Home home;String phase;StringBuilder log=new StringBuilder();
    public void onCreate(Bundle b){super.onCreate(b);phase=b.getString("phase","");start();}
    void check(boolean ok,String name){if(!ok)throw new AssertionError(name);log.append(name).append('\n');}
    void until(java.util.concurrent.Callable<Boolean> condition,String name)throws Exception{for(int i=0;i<160;i++){boolean[] ok={false};Throwable[] error={null};runOnMainSync(()->{try{ok[0]=condition.call();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new Exception(error[0]);if(ok[0]){check(true,name);return;}Thread.sleep(100);}throw new AssertionError(name);}
    JSONObject saved(Context c,String name)throws Exception{try(InputStream in=new FileInputStream(new File(c.getFilesDir(),name))){return new JSONObject(new String(Library.bytes(in,8*1024*1024),"UTF-8"));}}
    String accountDigest(Context c)throws Exception{SharedPreferences p=c.getSharedPreferences("account",0);byte[] bytes=(p.getString("secret","")+"|"+p.getString("iv","")).getBytes("UTF-8");StringBuilder text=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();}
    public void onStart(){Bundle result=new Bundle();try{
        Context c=getTargetContext();home=(Home)startActivitySync(new Intent(c,Home.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        until(()->home.deck!=null&&home.deck.restored&&home.libraryReady,"activity, catalog and service restored");
        android.content.pm.PackageInfo info=c.getPackageManager().getPackageInfo(c.getPackageName(),0);
        if(phase.equals("seed")){
            check(info.versionName.equals("0.3.7.5")&&info.versionCode==17,"seed runs on original 0.3.7.5 code 17");
            File audio=new File(c.getFilesDir(),"upgrade.wav");try(InputStream in=getContext().getAssets().open("03 Waves.wav");OutputStream out=new FileOutputStream(audio)){out.write(Library.bytes(in,8*1024*1024));}
            ArrayList<Track> songs=new ArrayList<>();for(int i=0;i<3;i++)songs.add(new Track(new JSONObject().put("id",Uri.fromFile(audio).toString()).put("title","Upgrade-"+i)));
            Library.save(c,"library",Track.pack(songs));Server server=new Server("http://127.0.0.1:18053","demo","demo");server.save(c);
            Track remote=new Track(new JSONObject().put("id","upgrade-server").put("title","Cached before upgrade").put("server",server.account()));CatalogStore.save(c,server.account(),Arrays.asList(remote),false);
            home.getPreferences(0).edit().putBoolean("dark",true).putInt("sort",2).commit();
            home.deck.prefs().edit().putBoolean("replayGain",true).commit();
            runOnMainSync(()->{home.local.clear();home.local.addAll(songs);home.deck.replace(songs,0);home.deck.select(1);home.deck.mode=2;home.category="歌曲";home.query="Upgrade";home.group="";home.go("本地");});
            until(()->home.deck.playing(),"original system engine plays selected song");
            runOnMainSync(()->home.deck.seek(7400));until(()->home.deck.position()>=7000,"original seek completed");
            Track next=new Track(new JSONObject(songs.get(2).data.toString()).put("title","Upgrade pending"));
            runOnMainSync(()->{home.deck.pauseTrack();home.deck.insert(next);home.saveUiState();home.deck.saveMark();});
            until(()->saved(c,"deck-snapshot").getJSONArray("queue").length()==4&&saved(c,"deck-position").getInt("mark")>=7000,"original snapshot and position flushed");
            JSONObject expected=new JSONObject().put("uid",c.getApplicationInfo().uid).put("accountDigest",accountDigest(c)).put("permission",c.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE));
            try(OutputStream out=new FileOutputStream(new File(c.getFilesDir(),"upgrade-expected.json"))){out.write(expected.toString().getBytes("UTF-8"));}
            check(home.deck.explicitNext.size()==1&&home.deck.history.size()==1,"original pending queue and history seeded");
        }else{
            check(info.versionName.equals("0.4.0")&&info.versionCode==18,"unified 0.4.0 code 18 installed");
            JSONObject expected=saved(c,"upgrade-expected.json");
            check(c.getApplicationInfo().uid==expected.getInt("uid"),"application UID retained by in-place upgrade");
            check(c.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)==expected.getInt("permission"),"storage permission retained");
            check(accountDigest(c).equals(expected.getString("accountDigest"))&&Server.load(c).secret.equals("demo"),"encrypted account retained and decrypts with existing keystore");
            check(home.local.size()==3&&home.deck.queue.size()==4,"library and queue retained");
            check(home.remote.size()==1&&home.remote.get(0).id().equals("upgrade-server")&&!home.catalogComplete,"partial server catalog retained");
            check(home.deck.index==1&&home.deck.mode==2&&home.deck.mark>=7000&&!home.deck.want,"selected song position mode and paused intent retained");
            check(home.deck.explicitNext.size()==1&&home.deck.history.size()==1,"pending next and playback history retained");
            check(home.getPreferences(0).getBoolean("dark",false)&&home.getPreferences(0).getInt("sort",-1)==2&&home.query.equals("Upgrade"),"theme sorting and search retained");
            check(home.deck.prefs().getBoolean("replayGain",false),"ReplayGain preference retained");
            if(phase.equals("verify")){
                runOnMainSync(()->home.deck.resumeTrack());
                until(()->home.deck.playing()&&(Integer)Deck.class.getDeclaredMethod("softwareFrames").invoke(home.deck)>0,"existing local file plays through native audio engine");
                runOnMainSync(()->{home.deck.seek(9200);home.deck.pauseTrack();});
                until(()->saved(c,"deck-position").getInt("mark")>=9200,"new progress saved");
            }else if(phase.equals("restart")){
                check(home.deck.mark>=9200,"new progress retained across process restart");
                runOnMainSync(()->{home.deck.mode=1;home.deck.step(1,false);});
                check(home.deck.current().title().equals("Upgrade pending"),"restored pending item overrides shuffle");
                until(()->home.deck.playing(),"restored pending item plays");
                runOnMainSync(()->{home.deck.step(-1,false);home.deck.pauseTrack();});
                check(home.deck.current().title().equals("Upgrade-1"),"previous follows retained listening history");
            }else throw new AssertionError("Unknown phase "+phase);
        }
        result.putString("result",log.toString());finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("result",log.toString()+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}}
}
