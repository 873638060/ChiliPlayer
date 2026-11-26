package car.salt.soft;

import android.content.*;
import android.util.AtomicFile;
import java.io.*;
import java.util.*;
import org.json.*;

/** Account identity and songs are committed together, independently of credentials. */
final class CatalogStore {
    static String owner(Context c){SharedPreferences p=c.getSharedPreferences("account",0);return p.getString("base","")+"\n"+p.getString("user","");}
    static synchronized ArrayList<Track> restore(Context c){
        String owner=owner(c);
        try{
            File path=new File(c.getFilesDir(),"server-catalog-v2");
            if(path.exists()||new File(path+".bak").exists()){
                JSONObject data=new JSONObject(new String(Library.bytes(new AtomicFile(path).openRead(),64*1024*1024),"UTF-8"));
                if(owner.equals(data.getString("owner"))){ArrayList<Track> songs=Track.unpack(data.getJSONArray("songs"));Journal.event(c,"服务器曲库恢复 "+songs.size()+" 首；完整="+data.optBoolean("complete"));return songs;}
            }
            if(owner.equals(c.getSharedPreferences("account",0).getString("catalogOwner",""))){
                JSONArray old=new JSONArray(new String(Library.bytes(new AtomicFile(new File(c.getFilesDir(),"server-catalog")).openRead(),64*1024*1024),"UTF-8"));
                ArrayList<Track> songs=Track.unpack(old);save(c,owner,songs,true);Journal.event(c,"服务器旧曲库恢复 "+songs.size()+" 首");return songs;
            }
            Journal.event(c,"服务器曲库无匹配账号缓存");
        }catch(Exception e){Journal.event(c,"服务器曲库恢复失败 · "+e.getClass().getSimpleName());}
        return new ArrayList<>();
    }
    static synchronized void save(Context c,String owner,Collection<Track> songs,boolean complete)throws Exception{
        if(!owner.equals(owner(c)))return;
        byte[] bytes=new JSONObject().put("owner",owner).put("complete",complete).put("songs",Track.pack(new ArrayList<>(songs))).toString().getBytes("UTF-8");
        AtomicFile file=new AtomicFile(new File(c.getFilesDir(),"server-catalog-v2"));FileOutputStream out=file.startWrite();
        try{out.write(bytes);file.finishWrite(out);}catch(IOException e){file.failWrite(out);throw e;}
    }
}
