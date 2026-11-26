package car.salt.player;
import android.app.*;
import android.os.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import org.json.*;

/** Only run on the dedicated emulator: changes its in-memory library and test queue. */
public class RemoteProbe extends Instrumentation {
    Home h;StringBuilder report=new StringBuilder();
    interface Task{void run()throws Exception;}
    void main(Task task)throws Exception{Throwable[] fail={null};runOnMainSync(()->{try{task.run();}catch(Throwable t){fail[0]=t;}});if(fail[0]!=null)throw new Exception(fail[0]);}
    void check(boolean ok,String name){if(!ok)throw new AssertionError(name);report.append("PASS ").append(name).append('\n');}
    ListView list(View v){if(v instanceof ListView)return (ListView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){ListView l=list(((ViewGroup)v).getChildAt(i));if(l!=null)return l;}return null;}
    void key(int code)throws Exception{main(()->{h.dispatchKeyEvent(new KeyEvent(0,code));h.dispatchKeyEvent(new KeyEvent(1,code));});waitForIdleSync();}
    public void onCreate(Bundle b){super.onCreate(b);start();}
    public void onStart(){Bundle result=new Bundle();try{
        h=(Home)startActivitySync(new Intent(getTargetContext(),Home.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));setInTouchMode(false);
        for(int i=0;i<80;i++){boolean[] ready={false};main(()->ready[0]=h.libraryReady&&h.deck!=null&&h.deck.restored);if(ready[0])break;Thread.sleep(100);}
        main(()->{h.deck.pauseTrack();h.local.clear();for(int i=0;i<3;i++)h.local.add(new Track(new JSONObject().put("id","file:///remote-probe-"+i+".wav").put("title","Track "+i).put("duration",180000)));h.query="";h.group="";h.category="歌曲";h.go("本地");h.remoteUi.active=true;});waitForIdleSync();
        main(()->{ListView l=list(h.root);l.requestFocus();l.setSelection(1);});waitForIdleSync();
        main(()->{ListView l=list(h.root);check(!l.getChildAt(0).hasFocusable(),"row children do not steal focus");h.render();});waitForIdleSync();
        main(()->check(list(h.root).hasFocus()&&list(h.root).getSelectedItemPosition()==1,"stable song focus restored after render: position="+list(h.root).getSelectedItemPosition()+" focus="+list(h.root).hasFocus()));
        main(()->{h.selecting=true;h.render();});waitForIdleSync();main(()->h.onBackPressed());waitForIdleSync();
        main(()->check(!h.selecting&&!h.isFinishing()&&h.page.equals("本地"),"back cancels multiselect without exiting"));
        main(()->{h.deck.queue.clear();h.deck.queue.add(h.local.get(0));h.deck.index=0;h.deck.ready=false;h.deck.mark=30000;h.go("歌词");});waitForIdleSync();
        main(()->{h.seek.requestFocus();});key(KeyEvent.KEYCODE_DPAD_RIGHT);
        main(()->check(h.remoteSeeking&&h.seek.getProgress()==40000&&h.deck.mark==30000,"right previews ten seconds without seeking"));
        key(KeyEvent.KEYCODE_DPAD_CENTER);main(()->check(!h.remoteSeeking&&h.deck.mark==40000,"confirm commits seek"));
        key(KeyEvent.KEYCODE_DPAD_LEFT);main(()->h.onBackPressed());main(()->check(!h.remoteSeeking&&h.deck.mark==40000&&h.page.equals("歌词"),"back cancels preview without leaving player"));
        key(KeyEvent.KEYCODE_DPAD_RIGHT);main(()->h.playIcon.requestFocus());main(()->check(!h.remoteSeeking&&h.deck.mark==40000,"leaving seek cancels preview"));
        main(()->{h.seek.requestFocus();h.deck.mark=179000;h.update();});key(KeyEvent.KEYCODE_DPAD_RIGHT);main(()->check(h.seek.getProgress()==180000,"seek clamps at duration"));
        main(()->{h.onBackPressed();h.onBackPressed();});waitForIdleSync();main(()->check(h.page.equals("本地"),"player back returns to library"));
        result.putString("stream",report.toString());finish(-1,result);
    }catch(Throwable e){result.putString("stream",report+"FAIL "+e);finish(0,result);}}
}
