package car.salt.player;

import android.app.*;
import android.content.*;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import android.view.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import com.un4seen.bass.BASS;

/** Regression checks for the soft.3 review. Only run on the test emulator. */
public class AuditProbe extends Instrumentation {
    Home home;Track track;boolean baseline;int failures;
    StringBuilder report=new StringBuilder();
    interface Task {void run()throws Exception;}
    public void onCreate(Bundle args){super.onCreate(args);baseline=args!=null&&"true".equals(args.getString("baseline"));start();}
    void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    void main(Task task)throws Exception{Throwable[] error={null};runOnMainSync(()->{try{task.run();}catch(Throwable ex){error[0]=ex;}});if(error[0]!=null)throw new Exception(error[0]);}
    void test(String name,Task task){try{task.run();report.append("PASS ").append(name).append('\n');}catch(Throwable ex){failures++;report.append("FAIL ").append(name).append(" ").append(ex).append('\n');}}
    void until(java.util.concurrent.Callable<Boolean> condition)throws Exception{for(int i=0;i<120;i++){boolean[] yes={false};main(()->yes[0]=condition.call());if(yes[0])return;Thread.sleep(100);}throw new AssertionError("Timed out");}
    Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    SoftPlayer player()throws Exception{return (SoftPlayer)field(home.deck,"audio");}
    void play()throws Exception{main(()->{home.deck.mode=0;home.deck.replace(Arrays.asList(track),0);});until(()->home.deck.playing());}
    void screenshot(String name)throws Exception{main(()->{android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(home.root.getWidth(),home.root.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);home.root.draw(new android.graphics.Canvas(bitmap));try(OutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),name))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();});}
    public void onStart(){Bundle result=new Bundle();try{
        Context c=getTargetContext();File file=new File(c.getFilesDir(),"audit.wav");try(InputStream in=getContext().getAssets().open("03 Waves.wav");OutputStream out=new FileOutputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))>0)out.write(b,0,n);}
        track=new Track(new JSONObject().put("id",Uri.fromFile(file).toString()).put("title","车机测试音轨").put("artist","轻椒音乐"));
        home=(Home)startActivitySync(new Intent(c,Home.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));until(()->home.deck!=null&&home.deck.restored&&home.libraryReady);
        test("Pause clears playback intent when native channel is not playing",()->{play();main(()->{SoftPlayer p=player();BASS.BASS_ChannelPause((Integer)field(p,"stream"));check(!p.isPlaying(),"native paused precondition");home.deck.pauseTrack();check(!(Boolean)field(p,"started"),"pause must reach engine even when isPlaying is false");});});
        test("Play-next overrides shuffle and previous returns to heard track",()->{main(()->{ArrayList<Track> songs=new ArrayList<>();for(int i=0;i<3;i++)songs.add(new Track(new JSONObject(track.data.toString()).put("title","Q"+i)));home.deck.replace(songs,0);home.deck.mode=1;((Random)field(home.deck,"random")).setSeed(42);Track next=new Track(new JSONObject(track.data.toString()).put("title","Explicit"));home.deck.insert(next);home.deck.step(1,false);check(home.deck.current().title().equals("Explicit"),"shuffle ignored explicit play-next");home.deck.step(-1,false);check(home.deck.current().title().equals("Q0"),"previous must follow listening history");});});
        test("Player touch targets are at least 48dp",()->{main(()->home.go("播放"));waitForIdleSync();main(()->{check(home.seek.getHeight()>=home.dp(48),"seek target is too short");for(String description:new String[]{"上一首","下一首","返回曲库"}){View v=find(home.root,description);check(v!=null&&v.getWidth()>=home.dp(48)&&v.getHeight()>=home.dp(48),description+" touch target too small");}});});
        screenshot(baseline?"audit-before.png":"audit-player.png");
        if(!baseline){
            test("Manually playing or promoting an explicit-next item consumes its priority",()->{main(()->{
                ArrayList<Track> songs=new ArrayList<>();for(int i=0;i<3;i++)songs.add(new Track(new JSONObject(track.data.toString()).put("title","Q"+i)));
                Track next=new Track(new JSONObject(track.data.toString()).put("title","Explicit"));
                home.deck.mode=0;home.deck.replace(songs,0);home.deck.insert(next);home.deck.select(1);home.deck.step(1,false);check(home.deck.current().title().equals("Q1"),"manually selected pending track repeated");
                home.deck.replace(songs,0);home.deck.insert(next);home.deck.remove(0);home.deck.resumeTrack();home.deck.step(1,false);check(home.deck.current().title().equals("Q1"),"promoted pending track repeated");
            });});
            test("Focus duck and restore preserve system volume and ReplayGain",()->{
                play();AudioManager am=(AudioManager)c.getSystemService(Context.AUDIO_SERVICE);int system=am.getStreamVolume(AudioManager.STREAM_MUSIC);AudioManager.OnAudioFocusChangeListener nav=change->{};
                main(()->{player().setGain(-6);check(am.requestAudioFocus(nav,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED,"navigation focus request");});
                try{until(()->channelVolume()<0.3f);main(()->check(home.deck.want&&home.deck.playing(),"duck must not pause music"));check(system==am.getStreamVolume(AudioManager.STREAM_MUSIC),"system volume changed");}
                finally{main(()->am.abandonAudioFocus(nav));}
                until(()->channelVolume()>0.99f);
                BASS.FloatValue gain=new BASS.FloatValue();main(()->{BASS.BASS_ChannelGetAttribute((Integer)field(player(),"stream"),BASS.BASS_ATTRIB_VOLDSP,gain);check(Math.abs(gain.value-0.501187f)<0.001,"duck changed ReplayGain");});
            });
            test("User pause during transient focus loss prevents automatic resume",()->{
                play();AudioManager am=(AudioManager)c.getSystemService(Context.AUDIO_SERVICE);AudioManager.OnAudioFocusChangeListener other=change->{};
                main(()->am.requestAudioFocus(other,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT));until(()->!home.deck.playing());
                main(()->home.deck.pauseTrack());main(()->am.abandonAudioFocus(other));Thread.sleep(500);main(()->check(!home.deck.want&&!home.deck.playing(),"manual pause was overridden"));
            });
            test("Transient focus loss resumes only previously requested playback",()->{
                play();AudioManager am=(AudioManager)c.getSystemService(Context.AUDIO_SERVICE);AudioManager.OnAudioFocusChangeListener other=change->{};
                main(()->am.requestAudioFocus(other,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT));until(()->!home.deck.playing());
                main(()->check(home.deck.want,"transient focus loss discarded user intent"));main(()->am.abandonAudioFocus(other));until(()->home.deck.playing());
            });
            test("Background freezes view updates while audio keeps playing; return refreshes",()->{
                play();main(()->home.go("播放"));waitForIdleSync();String[] text={""};
                main(()->{text[0]=home.clockText.getText().toString();callActivityOnStop(home);home.deck.seek(12000);});Thread.sleep(1600);
                main(()->{check(home.clockText.getText().toString().equals(text[0]),"background view was refreshed");check(home.deck.playing(),"background stopped audio");callActivityOnStart(home);home.deck.pauseTrack();check(home.clockText.getText().toString().contains("已暂停")&&!home.clockText.getText().toString().equals(text[0]),"return did not refresh playback status");});
            });
            test("Reopened activity refreshes progress when service was already playing",()->{
                play();main(()->home.finish());waitForIdleSync();
                home=(Home)startActivitySync(new Intent(c,Home.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));until(()->home.deck!=null&&home.deck.playing());
                main(()->home.go("播放"));waitForIdleSync();String[] text={""};main(()->text[0]=home.clockText.getText().toString());Thread.sleep(1700);
                main(()->check(!home.clockText.getText().toString().equals(text[0]),"rebound activity progress froze"));
            });
            screenshot("audit-player.png");main(()->home.go("本地"));waitForIdleSync();screenshot("audit-list.png");
        }
        main(()->home.deck.pauseTrack());
    }catch(Throwable ex){failures++;report.append(android.util.Log.getStackTraceString(ex));}
        result.putString("result",report.toString());finish(failures==0?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
    float channelVolume()throws Exception{BASS.FloatValue volume=new BASS.FloatValue();check(BASS.BASS_ChannelGetAttribute((Integer)field(player(),"stream"),BASS.BASS_ATTRIB_VOL,volume),"volume query");return volume.value;}
    View find(View view,String name){if(name.equals(view.getContentDescription()))return view;if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),name);if(found!=null)return found;}}return null;}
}
