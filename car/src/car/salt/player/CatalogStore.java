package car.salt.player;
import android.content.*;
import java.io.*;
import java.util.*;
import org.json.*;
final class CatalogStore {
    static final class Snapshot {final ArrayList<Track> songs=new ArrayList<>();boolean found,complete;String error="";}
    static String owner(Context c){SharedPreferences p=c.getSharedPreferences("account",0);return p.getString("base","")+"\n"+p.getString("user","");}
    static Snapshot load(Context c){
        Snapshot result=new Snapshot();String owner=owner(c);
        try{
            byte[] bytes=Store.read(c,"server-catalog-v2");
            if(bytes!=null){JSONObject data=new JSONObject(new String(bytes,"UTF-8"));
                if(owner.equals(data.getString("owner"))){result.songs.addAll(Track.unpack(data.getJSONArray("songs")));result.complete=data.optBoolean("complete");result.found=true;}
            }
            if(!result.found&&owner.equals(c.getSharedPreferences("account",0).getString("catalogOwner",""))){
                result.songs.addAll(Track.unpack(Library.load(c,"server-catalog")));result.found=true;result.complete=true;
                try{save(c,owner,result.songs,true);}catch(Exception e){result.error="旧曲库已恢复，但迁移保存失败";}
            }
            Journal.event(c,"服务器缓存恢复 "+result.songs.size()+" 首；完整="+result.complete);
        }catch(Exception e){result.error="服务器缓存读取失败，原文件已保留";Journal.event(c,result.error+" · "+e.getClass().getSimpleName());}
        return result;
    }
    static ArrayList<Track> restore(Context c){return load(c).songs;}
    static synchronized void save(Context c,String owner,Collection<Track> songs,boolean complete)throws Exception{
        if(!owner.equals(owner(c)))throw new IOException("账号已切换，已取消保存");
        Store.save(c,"server-catalog-v2",new JSONObject().put("owner",owner).put("complete",complete).put("songs",Track.pack(new ArrayList<>(songs))).toString().getBytes("UTF-8"));
    }
}
