package car.salt.player;

import org.json.*;
import java.util.*;

final class Track {
    final JSONObject data;
    Track(JSONObject data) { this.data = data; }
    String get(String k) { return data.optString(k,""); }
    String id() { return get("id"); }
    String title() { return get("title"); }
    boolean remote() { return !get("server").isEmpty(); }
    static JSONArray pack(List<Track> list) {
        JSONArray a=new JSONArray();
        for (Track t:list) a.put(t.data);
        return a;
    }
    static ArrayList<Track> unpack(JSONArray a) {
        ArrayList<Track> list=new ArrayList<>();
        if(a!=null) for(int i=0;i<a.length();i++) if(a.optJSONObject(i)!=null) list.add(new Track(a.optJSONObject(i)));
        return list;
    }
}
