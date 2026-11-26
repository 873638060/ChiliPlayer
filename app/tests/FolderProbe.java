package car.salt.player;
import android.app.Instrumentation;
import android.os.Bundle;
import android.net.Uri;
import android.content.Context;
import java.io.*;
import java.util.*;

/** Isolated scanner regression: does not modify the user's library or account. */
public class FolderProbe extends Instrumentation {
    boolean picker;
    public void onCreate(Bundle b){super.onCreate(b);picker=b!=null&&"true".equals(b.getString("picker"));start();}
    void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public void onStart(){
        Bundle result=new Bundle();File root=null;
        try{
            if(picker){
                Home h=(Home)startActivitySync(new android.content.Intent(getTargetContext(),Home.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                Thread.sleep(1500);final android.app.AlertDialog[] dialog={null};
                runOnMainSync(()->{try{FolderPicker p=new FolderPicker(h);java.lang.reflect.Field field=FolderPicker.class.getDeclaredField("dialog");field.setAccessible(true);dialog[0]=(android.app.AlertDialog)field.get(p);dialog[0].show();}catch(Exception e){throw new RuntimeException(e);}});
                Thread.sleep(1500);
                runOnMainSync(()->{check(dialog[0].isShowing(),"picker visible");check(!dialog[0].getButton(-1).isEnabled(),"cannot select storage list as a folder");dialog[0].dismiss();});
                result.putString("stream","PASS: Android 6 picker opens; root selection guard; dismissal\n");finish(-1,result);return;
            }
            Context c=getTargetContext();root=new File(c.getCacheDir(),"folder-probe-"+System.nanoTime());
            check(root.mkdirs(),"create isolated directory");File sub=new File(root,"子目录");check(sub.mkdir(),"create nested directory");
            File audio=new File(sub,"中文 歌曲.wav");
            try(InputStream in=getContext().getAssets().open("03 Waves.wav");OutputStream out=new FileOutputStream(audio)){
                byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
            }
            File lyric=new File(sub,"中文 歌曲.lrc");try(OutputStream out=new FileOutputStream(lyric)){out.write("[00:00.00]测试".getBytes("UTF-8"));}
            File loop=new File(sub,"loop");android.system.Os.symlink(root.getAbsolutePath(),loop.getAbsolutePath());
            File outside=new File(sub,"outside");android.system.Os.symlink(c.getCacheDir().getAbsolutePath(),outside.getAbsolutePath());
            Uri tree=Uri.fromFile(root);ArrayList<Track> tracks=Library.scan(c,tree,new ArrayList<>());
            check(tracks.size()==1,"nested scan excludes outside links and cycles");Track t=tracks.get(0);
            check(t.get("folder").equals("子目录/"),"relative folder");check(t.get("lrc").equals(Uri.fromFile(lyric.getCanonicalFile()).toString()),"sidecar lyrics");
            check(t.get("tree").equals(tree.toString()),"folder ownership");
            check(Library.scan(c,tree,tracks).size()==1,"rescan without duplicates");
            try(InputStream in=SourceAccess.open(c,Uri.parse(t.id()))){check(in.read()>=0,"playback source readable");}
            check(audio.delete(),"remove isolated test audio");
            try{Library.scan(c,tree,tracks);throw new AssertionError("empty directory must preserve previous catalog");}catch(Library.EmptyFolder expected){}
            check(Library.scan(c,tree,new ArrayList<>()).isEmpty(),"new empty directory");
            try{Library.scan(c,Uri.fromFile(new File(root,"missing")),tracks);throw new AssertionError("missing directory");}catch(IOException expected){}
            loop.delete();outside.delete();lyric.delete();sub.delete();root.delete();
            result.putString("stream","PASS: nested/Chinese paths, lyrics, rescan, file read, symlink boundary/cycle, empty and missing folder protection\n");finish(-1,result);
        }catch(Throwable e){result.putString("stream","FAIL: "+e);finish(0,result);}
    }
}
