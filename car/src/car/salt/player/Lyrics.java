package car.salt.player;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;
import org.json.*;

final class Lyrics {
    static String decode(byte[] bytes){
        if(bytes==null)return "";
        if(bytes.length>=2&&((bytes[0]==(byte)0xff&&bytes[1]==(byte)0xfe)||(bytes[0]==(byte)0xfe&&bytes[1]==(byte)0xff)))return new String(bytes,Charset.forName("UTF-16"));
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().replace("\uFEFF","");}
        catch(CharacterCodingException e){return new String(bytes,Charset.forName("GB18030"));}
    }
    static TreeMap<Integer,String> parse(String text){
        TreeMap<Integer,String> parsed=new TreeMap<>();long offset=0;
        Matcher adjustment=Pattern.compile("(?i)\\[offset:([+-]?\\d+)\\]").matcher(text);while(adjustment.find())try{offset=Long.parseLong(adjustment.group(1));}catch(Exception ignored){}
        Pattern pat=Pattern.compile("\\[(\\d{1,6}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
        for(String line:text.split("\n")){Matcher m=pat.matcher(line);String word=pat.matcher(line).replaceAll("").trim();while(m.find()){String f=(m.group(3)==null?"":m.group(3))+"000";long stamp=(Long.parseLong(m.group(1))*60+Integer.parseInt(m.group(2)))*1000+Integer.parseInt(f.substring(0,3))-offset;parsed.put((int)Math.max(0,Math.min(Integer.MAX_VALUE,stamp)),word);}}
        return parsed;
    }
    static String structured(JSONObject reply){
        JSONObject list=reply.optJSONObject("lyricsList");JSONArray all=list==null?null:list.optJSONArray("structuredLyrics");if(all==null)return "";
        JSONObject chosen=null;for(int i=0;i<all.length();i++){JSONObject entry=all.optJSONObject(i);if(entry!=null&&(chosen==null||entry.optBoolean("synced"))){chosen=entry;if(entry.optBoolean("synced"))break;}}
        if(chosen==null)return "";JSONArray lines=chosen.optJSONArray("line");if(lines==null)return "";StringBuilder text=new StringBuilder();long offset=chosen.optLong("offset");
        for(int i=0;i<lines.length();i++){JSONObject line=lines.optJSONObject(i);if(line==null)continue;if(chosen.optBoolean("synced")&&line.has("start")){long stamp=Math.max(0,line.optLong("start")-offset);text.append(String.format(Locale.ROOT,"[%02d:%02d.%03d]",stamp/60000,(stamp/1000)%60,stamp%1000));}text.append(line.optString("value")).append('\n');}return text.toString();
    }
}
