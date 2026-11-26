package car.salt.player;

import android.content.*;
import android.net.Uri;
import android.security.keystore.*;
import android.util.Base64;
import java.net.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.*;

final class Server {
    final String base,user,secret;
    Server(String address,String user,String secret) throws Exception {
        URI uri=new URI(address.trim());String scheme=uri.getScheme();
        if(!("https".equalsIgnoreCase(scheme)||"http".equalsIgnoreCase(scheme))||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new Exception("请输入完整的 HTTP 或 HTTPS 服务器地址");
        this.base=address.trim().replaceAll("/+$","");this.user=user.trim();this.secret=secret;
        if(this.user.isEmpty()||secret.isEmpty())throw new Exception("请输入账号和密码");
    }
    String account(){return base+"\n"+user;}
    static SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        String alias="car.salt.server";
        if(store.containsAlias(alias))return (SecretKey)store.getKey(alias,null);
        KeyGenerator g=KeyGenerator.getInstance("AES","AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes("GCM").setEncryptionPaddings("NoPadding").build());return g.generateKey();
    }
    void save(Context c) throws Exception {
        Cipher box=Cipher.getInstance("AES/GCM/NoPadding");box.init(Cipher.ENCRYPT_MODE,key());
        c.getSharedPreferences("account",0).edit().putString("base",base).putString("user",user)
        .putString("secret",enc(box.doFinal(secret.getBytes("UTF-8")))).putString("iv",enc(box.getIV())).commit();
    }
    static String enc(byte[] v){return Base64.encodeToString(v,Base64.NO_WRAP);}
    static Server load(Context c) throws Exception {
        SharedPreferences p=c.getSharedPreferences("account",0);if(!p.contains("secret"))throw new Exception("请连接服务器");
        Cipher box=Cipher.getInstance("AES/GCM/NoPadding");box.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(p.getString("iv",""),0)));
        return new Server(p.getString("base",""),p.getString("user",""),new String(box.doFinal(Base64.decode(p.getString("secret",""),0)),"UTF-8"));
    }
    String url(String action,String... params) throws Exception {
        String salt=UUID.randomUUID().toString().replace("-","");
        byte[] digest=MessageDigest.getInstance("MD5").digest((secret+salt).getBytes("UTF-8"));StringBuilder token=new StringBuilder();
        for(byte b:digest)token.append(String.format(Locale.US,"%02x",b&255));
        Uri.Builder b=Uri.parse(base+"/rest/"+action+".view").buildUpon().appendQueryParameter("u",user).appendQueryParameter("s",salt)
        .appendQueryParameter("t",token.toString()).appendQueryParameter("v","1.16.1").appendQueryParameter("c","NewSaltCar").appendQueryParameter("f","json");
        for(int i=0;i<params.length;i+=2)b.appendQueryParameter(params[i],params[i+1]);
        return b.build().toString();
    }
    static final class ReplyError extends Exception { ReplyError(String safeCode){super(safeCode);} }
    JSONObject call(String action,String... params)throws Exception {
        HttpURLConnection conn=(HttpURLConnection)new URL(url(action,params)).openConnection();
        try{conn.setConnectTimeout(9000);conn.setReadTimeout(15000);conn.setInstanceFollowRedirects(false);
            if(conn.getResponseCode()!=200)throw new ReplyError("HTTP "+conn.getResponseCode());
            JSONObject reply=new JSONObject(new String(Library.bytes(conn.getInputStream(),8*1024*1024),"UTF-8")).getJSONObject("subsonic-response");
            if(!"ok".equals(reply.optString("status")))throw new ReplyError("协议错误 "+(reply.optJSONObject("error")==null?0:reply.optJSONObject("error").optInt("code")));
            return reply;
        } finally{conn.disconnect();}
    }
    ArrayList<Track> tracks(JSONArray a)throws Exception {
        ArrayList<Track> out=new ArrayList<>();
        if(a!=null)for(int i=0;i<a.length();i++){JSONObject song=a.optJSONObject(i);
            if(song==null||song.optString("id","").isEmpty())continue;
            JSONObject d=new JSONObject().put("server",account()).put("id",song.getString("id")).put("title",song.optString("title")).put("artist",song.optString("artist")).put("album",song.optString("album")).put("cover",song.optString("coverArt")).put("duration",Math.min(Integer.MAX_VALUE,Math.max(0,song.optDouble("duration",0)*1000))).put("format",song.optString("suffix"));if(song.optJSONObject("replayGain")!=null)d.put("replayGain",song.getJSONObject("replayGain"));out.add(new Track(d));}
        return out;
    }
    String stream(Track t)throws Exception{
        if(!account().equals(t.get("server")))throw new Exception("请切回这首歌所属的服务器账号");
        return url("stream","id",t.id());
    }
}
