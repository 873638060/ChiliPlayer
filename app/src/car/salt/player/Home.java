package car.salt.player;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.text.InputType;
import android.text.TextUtils;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.net.*;

public final class Home extends Activity {
    private int INK,MUTED,ACCENT,PAPER,SOFT,surface;
    private boolean dark;
    final RemoteUi remoteUi=new RemoteUi(this);
    boolean remoteSeeking;
    private void applyPalette(boolean night){
        dark=night;
        INK=night?0xfff0eef5:0xff242632;
        MUTED=night?0xffc1baca:0xff655e6c;
        ACCENT=night?0xffdcc5ff:0xff645280;
        PAPER=night?0xff18171e:0xfff6f2f8;
        SOFT=night?0xff383144:0xffebe3f1;
        surface=night?0xff25232c:Color.WHITE;
    }
    private void toggleTheme(){
        getPreferences(0).edit().putBoolean("dark",!dark).apply();
        applyPalette(!dark);
        setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
        render();
    }
    private void topButton(LinearLayout tools,String kind,String name,int color,Runnable click){
        FrameLayout slot=new FrameLayout(this);
        tools.addView(slot,new LinearLayout.LayoutParams(0,dp(48),1));
        ImageView symbol=new ImageView(this);symbol.setImageDrawable(new Glyph(kind,color));
        slot.addView(symbol,new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));
        slot.setContentDescription(name);slot.setFocusable(true);slot.setOnClickListener(v->click.run());
    }
    ImageView miniCover,playIcon; TextView miniDetail;
    ImageView icon(LinearLayout parent,String kind,String description,int color,Runnable run){
        ImageView v=new ImageView(this);v.setImageDrawable(new Glyph(kind,color));v.setContentDescription(description);pad(v,10,10);
        parent.addView(v,new LinearLayout.LayoutParams(dp(48),dp(48)));v.setFocusable(true);v.setOnClickListener(w->run.run());return v;
    }
    void navigation(LinearLayout rail,String name,int color,Runnable run,boolean active){
        LinearLayout r=row();pad(r,8,0);GradientDrawable bg=shape(active?SOFT:surface,0);float radius=dp(12);boolean first=name.equals("歌曲")||name.equals("扫描音乐");bg.setCornerRadii(first?new float[]{radius,radius,radius,radius,0,0,0,0}:new float[]{0,0,0,0,radius,radius,radius,radius});r.setBackground(bg);rail.addView(r,new LinearLayout.LayoutParams(-1,dp(55)));
        ImageView symbol=new ImageView(this);symbol.setImageDrawable(new Glyph(name.equals("文件夹")?"folder":"disc",color));r.addView(symbol,new LinearLayout.LayoutParams(dp(23),dp(23)));
        TextView text=label(name,14,INK);pad(text,12,0);r.addView(text);r.setOnClickListener(v->run.run());
    }
    final ExecutorService io=Executors.newFixedThreadPool(3);
    final Handler timer=new Handler();
    Deck deck; boolean bound,scanning,libraryReady;boolean uiActive,seeking;
    static final Object scanLock=new Object();static final java.util.concurrent.atomic.AtomicInteger scanSerial=new java.util.concurrent.atomic.AtomicInteger();
    String libraryProblem="";boolean catalogComplete;ArrayList<Track> pendingCatalog;String pendingOwner="";boolean pendingComplete;
    String page="本地",category="歌曲",group="",query="",remoteTitle="",serverSearch="",serverNote="";
    volatile int request;int albumOffset;boolean remoteBusy,more;
    final ArrayList<Track> local=new ArrayList<>(),remote=new ArrayList<>(),visible=new ArrayList<>();
    JSONArray albums=new JSONArray();
    LinearLayout root,body,content,bar;TextView mini,play,clockText,lyricsText; SeekBar seek;
    ImageView art;Track rendered;
    final TreeMap<Integer,String> lines=new TreeMap<>();
    final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName n,IBinder b){deck=((Deck.Link)b).service();deck.change=()->changed();render();changed();}
        public void onServiceDisconnected(ComponentName n){deck=null;render();}
    };
    public void onCreate(Bundle b){applyPalette(getPreferences(0).getBoolean("dark",false));setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);super.onCreate(b);getWindow().setStatusBarColor(PAPER);getWindow().setNavigationBarColor(INK);

        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        restoreUi();
        io.execute(()->{
            ArrayList<Track> loaded=new ArrayList<>();String problem="";
            try{loaded.addAll(Track.unpack(Library.load(this,"library")));}catch(Exception e){problem="本地曲库读取失败，原数据已保留";}
            CatalogStore.Snapshot snapshot=CatalogStore.load(this);final String warning=problem;
            ui(()->{local.addAll(loaded);remote.addAll(snapshot.songs);catalogLoaded=snapshot.found||!snapshot.error.isEmpty();catalogComplete=snapshot.complete;
                serverNote=!snapshot.error.isEmpty()?snapshot.error:snapshot.found&&!snapshot.complete?"曲库尚未读取完整，点击补全继续读取":"";
                libraryProblem=warning;libraryReady=true;Journal.event(this,"恢复本地曲库 "+local.size()+" 首");render();});
        });
        if(b!=null){page=b.getString("page","本地");category=b.getString("category","歌曲");group=b.getString("group","");query=b.getString("query","");}
        render();bound=bindService(new Intent(this,Deck.class),connection,BIND_AUTO_CREATE);
        if(checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},90);}
    public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==90){covers.evictAll();retryCoverAfter.evictAll();render();
            Journal.event(this,"存储读取权限 "+(results.length>0&&results[0]==0?"已授权":"未授权"));
            if(results.length>0&&results[0]==0&&deck!=null&&deck.current()!=null&&!deck.current().remote()&&!deck.message.isEmpty())deck.resumeTrack();
        }
    }
    protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("page",page);state.putString("category",category);state.putString("group",group);state.putString("query",query);}
    void restoreUi(){try{JSONObject state=new JSONObject(getPreferences(0).getString("uiState","{}"));page=state.optString("page","本地");category=state.optString("category","歌曲");group=state.optString("group");query=state.optString("query");serverSearch=state.optString("serverSearch");returnPage=state.optString("returnPage","本地");JSONObject positions=state.optJSONObject("positions");if(positions!=null){Iterator<String> keys=positions.keys();while(keys.hasNext()){String key=keys.next();JSONArray value=positions.getJSONArray(key);listPositions.put(key,new int[]{value.getInt(0),value.getInt(1)});}}}catch(Exception e){Journal.event(this,"界面状态恢复失败");}}
    final Runnable saveUiLater=()->saveUiState();
    void scheduleUiSave(){timer.removeCallbacks(saveUiLater);timer.postDelayed(saveUiLater,350);}
    protected void onPause(){super.onPause();if(remoteSeeking){remoteSeeking=false;seeking=false;}saveUiState();}
    protected void onStart(){super.onStart();uiActive=true;if(deck!=null)changed();timer.removeCallbacks(tick);timer.post(tick);}
    protected void onStop(){uiActive=false;timer.removeCallbacks(tick);super.onStop();saveUiState();}

    void saveUiState(){if(!libraryReady)return;try{JSONObject state=new JSONObject().put("page",page).put("category",category).put("group",group).put("query",query).put("serverSearch",serverSearch).put("returnPage",returnPage);JSONObject positions=new JSONObject();for(Map.Entry<String,int[]> e:listPositions.entrySet())positions.put(e.getKey(),new JSONArray().put(e.getValue()[0]).put(e.getValue()[1]));state.put("positions",positions);getPreferences(0).edit().putString("uiState",state.toString()).apply();}catch(Exception e){Journal.event(this,"界面状态保存失败");}}
    public void onDestroy(){if(scanning)synchronized(scanLock){scanSerial.incrementAndGet();}timer.removeCallbacks(tick);timer.removeCallbacks(saveUiLater);if(deck!=null)deck.change=null;if(bound)unbindService(connection);io.shutdownNow();super.onDestroy();}
    int dp(float n){return Math.round(getResources().getDisplayMetrics().density*n);}
    LinearLayout col(){LinearLayout v=new LinearLayout(this);v.setOrientation(1);return v;}
    LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    void pad(View v,int a,int b){v.setPadding(dp(a),dp(b),dp(a),dp(b));}
    GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    TextView label(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    void fill(LinearLayout l,View v){boolean vertical=l.getOrientation()==1;l.addView(v,new LinearLayout.LayoutParams(vertical?-1:0,vertical?0:-2,1));}
    TextView action(LinearLayout l,String name,Runnable fn){TextView t=label(name,16,ACCENT);pad(t,12,6);t.setGravity(Gravity.CENTER);t.setMinHeight(dp(48));t.setMinWidth(dp(48));t.setFocusable(true);t.setOnClickListener(v->fn.run());l.addView(t);return t;}
    void selected(TextView t,boolean yes){if(yes){t.setBackground(shape(SOFT,14));t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);}}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    String detail(Track t){String artist=t.get("artist");return (artist.isEmpty()?"未知歌手":artist)+(t.get("album").isEmpty()?"":" · "+t.get("album"));}
    String returnPage="本地",lastPlaybackMessage="";
    void go(String s){if((s.equals("播放")||s.equals("歌词"))&&!page.equals("歌词")&&!page.equals("播放"))returnPage=page;page=s;render();scheduleUiSave();if(s.equals("服务器")&&libraryReady&&!catalogLoaded&&hasAccount()&&!remoteBusy)loadAlbums(0);}
    boolean hasAccount(){return getSharedPreferences("account",0).contains("secret");}
    void render(){remoteUi.remember();remoteSeeking=false;if(page.equals("队列")||page.equals("收藏"))page="本地";if(page.equals("播放"))page="歌词";seeking=false;seek=null;clockText=null;lyricsText=null;mini=null;miniDetail=null;miniCover=null;play=null;playIcon=null;art=null;rendered=null;
        boolean fullPlayer=page.equals("播放")||page.equals("歌词");getWindow().setStatusBarColor(PAPER);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR));
        root=col();root.setBackgroundColor(PAPER);setContentView(root);
        if(page.equals("播放")||page.equals("歌词")){content=root;player();update();remoteUi.install(ACCENT);return;}
        body=row();root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        ScrollView railScroll=new ScrollView(this);body.addView(railScroll,new LinearLayout.LayoutParams(0,-1,0.29f));
        LinearLayout rail=col();pad(rail,16,12);railScroll.addView(rail);
        LinearLayout tools=row();tools.setMinimumHeight(dp(52));tools.setBackground(shape(surface,14));rail.addView(tools);
        topButton(tools,"settings","设置与日志",0xffbd9650,()->go("设置"));
        topButton(tools,"theme",dark?"切换到明亮主题":"切换到暗色主题",ACCENT,this::toggleTheme);
        Space gap=new Space(this);rail.addView(gap,new LinearLayout.LayoutParams(1,dp(20)));
        String[] names={"歌曲","文件夹"};int[] colors={0xff74aa88,0xff8985be};
        for(int i=0;i<names.length;i++){final String s=names[i];navigation(rail,s,colors[i],()->{category=s;group="";go("本地");},page.equals("本地")&&category.equals(s));}
        Space gap2=new Space(this);rail.addView(gap2,new LinearLayout.LayoutParams(1,dp(18)));
        navigation(rail,"扫描音乐",0xffac80cc,()->go("扫描音乐"),page.equals("扫描音乐"));
        navigation(rail,"服务器曲库",0xffcba550,()->go("服务器"),page.equals("服务器"));
        content=col();pad(content,18,4);body.addView(content,new LinearLayout.LayoutParams(0,-1,0.71f));
        if(page.equals("播放")||page.equals("歌词"))player();
        else if(page.equals("服务器"))serverPage();
        else if(page.equals("本地"))localPage();
        else if(page.equals("设置"))settings();
        else if(page.equals("扫描音乐"))folderPage();
        else localPage();
        if(!page.equals("播放")&&!page.equals("歌词")){
            bar=row();bar.setBackgroundColor(surface);pad(bar,10,0);root.addView(bar,-1,dp(74));
            miniCover=icon(bar,"disc","展开播放器",0xfffdfaff,()->go("播放"));miniCover.setBackground(shape(SOFT,5));miniCover.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));
            mini=label("",16,INK);mini.setSingleLine();mini.setEllipsize(TextUtils.TruncateAt.END);LinearLayout miniText=col();pad(miniText,12,0);miniText.addView(mini);miniDetail=label("",11,MUTED);miniDetail.setSingleLine();miniDetail.setEllipsize(TextUtils.TruncateAt.END);miniText.addView(miniDetail);fill(bar,miniText);miniText.setOnClickListener(v->go("播放"));
            playIcon=icon(bar,"play","播放",INK,()->{if(deck!=null)deck.toggle();});icon(bar,"next","下一首",INK,()->{if(deck!=null)deck.step(1,false);});}
        update();remoteUi.install(ACCENT);}
    LinearLayout header(String title){LinearLayout h=row();h.setMinimumHeight(dp(52));content.addView(h);TextView t=label(title,16,INK);t.setSingleLine();t.setEllipsize(TextUtils.TruncateAt.END);fill(h,t);return h;}
    String folderKey(Track t){return t.get("tree")+"\n"+t.get("folder");}
    String folderName(Track t){String folder=t.get("folder");return folder.isEmpty()||folder.equals("/")?"根目录":folder;}
    String folderRoot(Track t){try{return android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(t.get("tree")));}catch(Exception e){return t.get("tree");}}
    String selectionScope="";
    String trackKey(Track t){return t.get("server")+"\n"+t.id();}
    void syncSelectionScope(){String key=page+"|"+category+"|"+group+"|"+query+"|"+serverSearch;if(!key.equals(selectionScope)){selectionScope=key;selecting=false;chosen.clear();}}
    ArrayList<Track> selectedTracks(List<Track> tracks){ArrayList<Track> result=new ArrayList<>();for(Track t:tracks)if(!selecting||chosen.contains(trackKey(t)))result.add(t);return result;}
    void shuffleTracks(List<Track> tracks){ArrayList<Track> scope=selectedTracks(tracks);if(deck==null||scope.isEmpty())return;deck.mode=1;selecting=false;chosen.clear();deck.replace(scope,new Random().nextInt(scope.size()));go("播放");}
    void songTools(List<Track> tracks){
        syncSelectionScope();LinearLayout tools=row();content.addView(tools);
        ImageView shuffle=icon(tools,"shuffle","随机播放",INK,()->shuffleTracks(tracks));shuffle.setEnabled(!selectedTracks(tracks).isEmpty()&&libraryReady);shuffle.setAlpha(shuffle.isEnabled()?1f:.35f);
        String caption=!libraryReady?"正在恢复曲库…":scanning&&page.equals("本地")?"正在读取音乐…":tracks.size()+" 首"+(selecting?" · 已选 "+selectedTracks(tracks).size():"");
        TextView count=label(caption,12,INK);count.setContentDescription("当前歌曲数量");fill(tools,count);
        icon(tools,"sort","排序",INK,this::sortMenu);icon(tools,"select","多选歌曲",selecting?ACCENT:INK,()->{selecting=!selecting;chosen.clear();render();});
    }
    void localPage(){
        if(libraryReady&&category.equals("文件夹")&&!group.isEmpty()&&!group.contains("\n")){
            HashSet<String> candidates=new HashSet<>();for(Track t:local)if(t.get("folder").equals(group))candidates.add(folderKey(t));
            group=candidates.size()==1?candidates.iterator().next():"";
        }
        boolean folderList=category.equals("文件夹")&&group.isEmpty();
        TreeMap<String,Track> directories=new TreeMap<>();ArrayList<Track> matches=new ArrayList<>();String heading=category;
        for(Track t:local){
            if(!query.isEmpty()&&!(t.title()+detail(t)+t.get("file")).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))continue;
            directories.put(folderKey(t),t);
            if(category.equals("歌曲")||folderKey(t).equals(group))matches.add(t);
        }
        if(!group.isEmpty())for(Track t:local)if(folderKey(t).equals(group)){heading=folderName(t);break;}
        LinearLayout h=header(heading);if(category.equals("文件夹")&&!group.isEmpty())action(h,"全部文件夹",()->{group="";render();});icon(h,"search","搜索本地曲库",INK,()->askSearch(false));
        if(folderList){syncSelectionScope();content.addView(label(directories.size()+" 个文件夹",12,MUTED));}
        else{sortTracks(matches);songTools(matches);}
        if(!libraryProblem.isEmpty())content.addView(label(libraryProblem,13,ACCENT));
        if(!query.isEmpty())action(content,"清除搜索 · "+query,()->{query="";render();});
        if(folderList){ArrayList<String> keys=new ArrayList<>(directories.keySet()),names=new ArrayList<>(),roots=new ArrayList<>();for(String key:keys){Track t=directories.get(key);names.add(folderName(t));roots.add(folderRoot(t));}items(names,roots,i->{group=keys.get(i);render();},null);}
        else songs(matches);
        if(local.isEmpty()&&!scanning&&libraryReady)action(content,"选择音乐文件夹",this::pick);
    }
    interface Hit{void at(int i);}
    boolean selecting;
    final Map<String,Set<ImageView>> waitingCovers=new HashMap<>();
    final android.util.LruCache<String,Long> retryCoverAfter=new android.util.LruCache<>(1024);
    final HashSet<String> chosen=new HashSet<>();
    final android.util.LruCache<String,Bitmap> covers=new android.util.LruCache<String,Bitmap>(4*1024*1024){protected int sizeOf(String key,Bitmap b){return b.getByteCount();}};
    void sortMenu(){new AlertDialog.Builder(this).setTitle("歌曲排序").setSingleChoiceItems((page.equals("服务器")?new String[]{"歌曲名称","歌手","专辑"}:new String[]{"歌曲名称","歌手","专辑","文件名","最近修改"}),getPreferences(0).getInt(page.equals("服务器")?"serverSort":"sort",0),(d,n)->{getPreferences(0).edit().putInt(page.equals("服务器")?"serverSort":"sort",n).apply();d.dismiss();render();}).show();}
    void sortTracks(List<Track> tracks){
        int sort=getPreferences(0).getInt(page.equals("服务器")?"serverSort":"sort",0);java.text.Collator collator=java.text.Collator.getInstance(Locale.CHINA);
        Collections.sort(tracks,(a,b)->{if(sort==4)return Long.compare(b.data.optLong("mtime"),a.data.optLong("mtime"));String key=new String[]{"title","artist","album","file"}[sort];int result=Integer.compare(indexRank(a.get(key)),indexRank(b.get(key)));if(result==0)result=collator.compare(a.get(key),b.get(key));return result!=0?result:collator.compare(a.title(),b.title());});
    }
    static final String INDEX="0ABCDEFGHIJKLMNOPQRSTUVWXYZ#";
    static int indexRank(String title){return INDEX.indexOf(initial(title));}
    static String initial(String title){
        if(title.isEmpty())return "#";
        String first=title.substring(0,1).toUpperCase(Locale.ROOT);char letter=first.charAt(0);
        if(letter>='0'&&letter<='9')return "0";if(letter>='A'&&letter<='Z')return String.valueOf(letter);
        try{byte[] bytes=first.getBytes("GB2312");if(bytes.length!=2)return "#";int code=(bytes[0]&255)*256+(bytes[1]&255)-65536;
            int[] boundaries={-20319,-20284,-19776,-19219,-18711,-18527,-18240,-17923,-17418,-16475,-16213,-15641,-15166,-14923,-14915,-14631,-14150,-14091,-13319,-12839,-12557,-11848,-11056};
            String letters="ABCDEFGHJKLMNOPQRSTWXYZ";
            if(code<boundaries[0]||code>-10247)return "#";
            for(int i=boundaries.length-1;i>=0;i--)if(code>=boundaries[i])return letters.substring(i,i+1);
        }catch(Exception ignored){}return "#";
    }
    String mediaKey(Track t){return t.remote()?t.get("server")+"|"+t.id():t.id()+"|"+t.get("size")+"|"+t.get("mtime");}
    byte[] coverBytes(Track track)throws Exception{
        byte[] data;
        if(track.remote()){
            if(track.get("cover").isEmpty())return null;
            Server server=Server.load(this);
            if(!server.account().equals(track.get("server")))return null;
            URL url=new URL(server.url("getCoverArt","id",track.get("cover"),"size","384"));
            HttpURLConnection connection=(HttpURLConnection)url.openConnection();
            try{
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(5000);connection.setReadTimeout(7000);
                if(connection.getResponseCode()!=200)return null;
                data=Library.bytes(connection.getInputStream(),4*1024*1024);
            }finally{connection.disconnect();}
        }else{
            MediaMetadataRetriever reader=new MediaMetadataRetriever();
            try{SourceAccess.metadata(this,reader,Uri.parse(track.id()));data=reader.getEmbeddedPicture();}
            finally{reader.release();}
        }
        return data;
    }
    Bitmap readCover(Track track,int limit)throws Exception{
        String key="cover|"+(track.remote()?track.get("server")+"|"+track.get("cover"):mediaKey(track));
        byte[] data=BlobCache.get(this,key,4*1024*1024,()->coverBytes(track));
        if(data==null)return null;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(data,0,data.length,options);options.inSampleSize=1;
        while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>limit)options.inSampleSize*=2;
        options.inJustDecodeBounds=false;return BitmapFactory.decodeByteArray(data,0,data.length,options);
    }
    void thumbnail(ImageView view,Track track){
        String key=track==null?"":track.remote()?track.get("server")+"\n"+track.get("cover"):mediaKey(track);
        if(!key.equals(view.getTag())){
            view.setTag(key);pad(view,10,10);
            view.setImageDrawable(new Glyph("disc",0xfffdfaff));
        }
        if(track==null||(track.remote()&&track.get("cover").isEmpty()))return;
        Bitmap cached=covers.get(key);
        if(cached!=null){
            if(!(view.getDrawable() instanceof BitmapDrawable)||((BitmapDrawable)view.getDrawable()).getBitmap()!=cached){pad(view,0,0);view.setImageBitmap(cached);}
            return;
        }
        Long retry=retryCoverAfter.get(key);
        if(retry!=null&&android.os.SystemClock.elapsedRealtime()<retry)return;
        Set<ImageView> listeners=waitingCovers.get(key);
        if(listeners!=null){listeners.add(view);return;}
        listeners=Collections.newSetFromMap(new WeakHashMap<ImageView,Boolean>());listeners.add(view);waitingCovers.put(key,listeners);
        io.execute(()->{
            Bitmap bitmap=null;try{bitmap=readCover(track,128);}catch(Exception ignored){}
            final Bitmap result=bitmap;
            ui(()->{Set<ImageView> targets=waitingCovers.remove(key);
                if(result==null){retryCoverAfter.put(key,android.os.SystemClock.elapsedRealtime()+60000);return;}
                covers.put(key,result);
                for(ImageView target:targets)if(key.equals(target.getTag())){pad(target,0,0);target.setImageBitmap(result);}
            });
        });
    }
    final Map<String,int[]> listPositions=new HashMap<>();
    void songs(List<Track> list){
        final ArrayList<Track> entries=new ArrayList<>(list);
        if(selecting){LinearLayout tools=row();content.addView(tools);action(tools,"全选",()->{for(Track t:entries)chosen.add(trackKey(t));render();});action(tools,"播放所选",()->{ArrayList<Track> result=new ArrayList<>();for(Track t:entries)if(chosen.contains(trackKey(t)))result.add(t);if(deck!=null&&!result.isEmpty()){selecting=false;deck.replace(result,0);go("播放");}});action(tools,"取消",()->{selecting=false;chosen.clear();render();});}
        FrameLayout indexed=new FrameLayout(this);fill(content,indexed);LinearLayout area=row();indexed.addView(area,new FrameLayout.LayoutParams(-1,-1));ListView lv=new ListView(this);lv.setDivider(new android.graphics.drawable.ColorDrawable(PAPER));lv.setDividerHeight(dp(6));area.addView(lv,new LinearLayout.LayoutParams(0,-1,1));
        lv.setAdapter(new BaseAdapter(){public int getCount(){return entries.size();}public Object getItem(int i){return entries.get(i);}public long getItemId(int i){return i;}
            public View getView(int i,View reused,ViewGroup parent){Track track=entries.get(i);LinearLayout r=(LinearLayout)reused;
                if(r==null){r=row();pad(r,0,7);r.setMinimumHeight(dp(72));ImageView cover=new ImageView(Home.this);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);cover.setBackground(shape(SOFT,5));pad(cover,12,12);r.addView(cover,new LinearLayout.LayoutParams(dp(48),dp(48)));
                    LinearLayout text=col();pad(text,12,0);TextView title=label("",15,INK);title.setSingleLine();title.setEllipsize(TextUtils.TruncateAt.END);text.addView(title);LinearLayout tags=row();text.addView(tags);TextView badge=label("",10,ACCENT);pad(badge,5,0);badge.setBackground(shape(SOFT,4));tags.addView(badge);TextView artist=label("",11,MUTED);artist.setSingleLine();artist.setEllipsize(TextUtils.TruncateAt.END);LinearLayout.LayoutParams meta=new LinearLayout.LayoutParams(-2,-2);meta.leftMargin=dp(8);tags.addView(artist,meta);fill(r,text);
                    icon(r,"addNext","下一首播放",ACCENT,()->{});}
                thumbnail((ImageView)r.getChildAt(0),track);LinearLayout text=(LinearLayout)r.getChildAt(1);((TextView)text.getChildAt(0)).setText((selecting?(chosen.contains(trackKey(track))?"☑  ":"☐  "):"")+track.title());
                r.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);String file=track.get("file");int dot=file.lastIndexOf('.');String format=dot>=0?file.substring(dot+1).toUpperCase(Locale.ROOT):track.get("format").toUpperCase(Locale.ROOT);
                LinearLayout tags=(LinearLayout)text.getChildAt(1);TextView badge=(TextView)tags.getChildAt(0);badge.setText(format);badge.setVisibility(format.isEmpty()?View.GONE:View.VISIBLE);((TextView)tags.getChildAt(1)).setText(detail(track));
                r.setBackgroundColor(deck!=null&&same(track,deck.current())?SOFT:Color.TRANSPARENT);
                r.getChildAt(2).setOnClickListener(v->{if(deck!=null){deck.insert(track);toast("已加入下一首");}});return r;
            }});
        lv.setOnItemClickListener((parent,v,i,id)->{Track t=entries.get(i);if(selecting){if(!chosen.remove(trackKey(t)))chosen.add(trackKey(t));render();return;}if(deck==null)return;deck.replace(entries,i);((BaseAdapter)lv.getAdapter()).notifyDataSetChanged();});
        final String positionKey=page+"|"+category+"|"+group+"|"+query+"|"+serverSearch+"|"+getPreferences(0).getInt(page.equals("服务器")?"serverSort":"sort",0);
        int[] savedPosition=listPositions.get(positionKey);if(savedPosition!=null)lv.setSelectionFromTop(savedPosition[0]<entries.size()?savedPosition[0]:0,savedPosition[0]<entries.size()?savedPosition[1]:0);
        lv.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView v,int state){}public void onScroll(AbsListView v,int first,int count,int total){if(v.getChildCount()>0){if(listPositions.size()>64)listPositions.clear();listPositions.put(positionKey,new int[]{first,v.getChildAt(0).getTop()});scheduleUiSave();}}});
        lv.setOnItemLongClickListener((p,v,i,id)->{trackMenu(entries.get(i));return true;});
        if(page.equals("本地")||page.equals("服务器")){
            TextView bubble=label("",32,ACCENT);bubble.setGravity(Gravity.CENTER);bubble.setBackground(shape(surface,28));bubble.setElevation(dp(6));bubble.setVisibility(View.GONE);bubble.setContentDescription("当前索引提示");
            FrameLayout.LayoutParams bubbleParams=new FrameLayout.LayoutParams(dp(56),dp(56),Gravity.TOP|Gravity.RIGHT);bubbleParams.rightMargin=dp(40);indexed.addView(bubble,bubbleParams);
            View alphabet=new View(this){
                final android.graphics.Paint pen=new android.graphics.Paint(1);
                protected void onDraw(android.graphics.Canvas canvas){
                    float step=getHeight()/(float)INDEX.length();pen.setColor(MUTED);pen.setTextAlign(android.graphics.Paint.Align.CENTER);pen.setTextSize(Math.min(dp(10),step*0.76f));
                    for(int i=0;i<INDEX.length();i++){String letter=INDEX.substring(i,i+1);pen.setColor(letter.equals(getTag())?ACCENT:MUTED);pen.setFakeBoldText(letter.equals(getTag()));canvas.drawText(letter,getWidth()-dp(7),step*(i+.5f)-(pen.ascent()+pen.descent())/2,pen);}
                }
            };alphabet.setContentDescription("歌曲首字母索引");alphabet.setFocusable(true);
            alphabet.setOnKeyListener((v,key,event)->{
                if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){if(event.getAction()==KeyEvent.ACTION_DOWN){int n=v.getTag()==null?0:INDEX.indexOf(v.getTag().toString());n=Math.max(0,Math.min(INDEX.length()-1,n+(key==KeyEvent.KEYCODE_DPAD_DOWN?1:-1)));v.setTag(INDEX.substring(n,n+1));v.invalidate();}return true;}
                if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){if(event.getAction()==KeyEvent.ACTION_UP){String letter=v.getTag()==null?"0":v.getTag().toString();int sort=getPreferences(0).getInt(page.equals("服务器")?"serverSort":"sort",0);for(int i=0;i<entries.size();i++)if(initial(entries.get(i).get(new String[]{"title","artist","album","file","title"}[sort])).equals(letter)){lv.requestFocus();lv.setSelection(i);return true;}toast("该字母下没有歌曲");}return true;}return false;
            });area.addView(alphabet,new LinearLayout.LayoutParams(dp(32),-1));
            alphabet.setOnTouchListener((v,event)->{if(event.getAction()==android.view.MotionEvent.ACTION_DOWN||event.getAction()==android.view.MotionEvent.ACTION_MOVE){int letter=Math.min(INDEX.length()-1,Math.max(0,(int)(event.getY()*INDEX.length()/Math.max(1,v.getHeight()))));String key=INDEX.substring(letter,letter+1);v.setTag(key);v.invalidate();bubble.setText(key);bubbleParams.topMargin=Math.max(0,Math.min(indexed.getHeight()-dp(56),(int)event.getY()-dp(28)));bubble.setLayoutParams(bubbleParams);bubble.setVisibility(View.VISIBLE);v.getParent().requestDisallowInterceptTouchEvent(true);for(int i=0;i<entries.size();i++){int sort=getPreferences(0).getInt(page.equals("服务器")?"serverSort":"sort",0);String title=entries.get(i).get(new String[]{"title","artist","album","file","title"}[sort]);if(initial(title).equals(key)){lv.setSelection(i);break;}}return true;}if(event.getAction()==android.view.MotionEvent.ACTION_UP||event.getAction()==android.view.MotionEvent.ACTION_CANCEL){bubble.setVisibility(View.GONE);v.setTag(null);v.invalidate();v.getParent().requestDisallowInterceptTouchEvent(false);if(event.getAction()==android.view.MotionEvent.ACTION_UP)v.performClick();}return true;});}
    }
    void items(List<String> titles,List<String> subs,Hit click,Hit hold){
        ListView lv=new ListView(this);lv.setDivider(new android.graphics.drawable.ColorDrawable(PAPER));lv.setDividerHeight(dp(6));fill(content,lv);
        lv.setAdapter(new BaseAdapter(){public int getCount(){return titles.size();}public Object getItem(int i){return titles.get(i);}public long getItemId(int i){return i;}
            public View getView(int i,View reuse,ViewGroup parent){LinearLayout r=(LinearLayout)reuse;
                if(r==null){r=row();pad(r,0,5);r.setMinimumHeight(dp(72));
                    ImageView thumbnail=new ImageView(Home.this);thumbnail.setImageDrawable(new Glyph("disc",0xffc7c0d0));thumbnail.setBackground(shape(SOFT,5));pad(thumbnail,8,8);r.addView(thumbnail,new LinearLayout.LayoutParams(dp(48),dp(48)));
                    LinearLayout text=col();pad(text,12,0);for(int k=0;k<2;k++){TextView t=label("",k==0?15:11,k==0?INK:MUTED);t.setSingleLine();t.setEllipsize(TextUtils.TruncateAt.END);text.addView(t);}fill(r,text);
                    ImageView more=new ImageView(Home.this);more.setImageDrawable(new Glyph("chevron",MUTED));pad(more,12,12);r.addView(more,new LinearLayout.LayoutParams(dp(42),dp(44)));}
                r.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);LinearLayout text=(LinearLayout)r.getChildAt(1);((TextView)text.getChildAt(0)).setText(titles.get(i));TextView sub=(TextView)text.getChildAt(1);sub.setText(subs==null?"":subs.get(i));sub.setVisibility(subs==null?View.GONE:View.VISIBLE);
                r.getChildAt(2).setOnClickListener(v->{if(hold!=null)hold.at(i);else click.at(i);});return r;}});
        lv.setOnItemClickListener((p,v,i,id)->click.at(i));if(hold!=null)lv.setOnItemLongClickListener((p,v,i,id)->{hold.at(i);return true;});}
    boolean same(Track a,Track b){return a!=null&&b!=null&&a.id().equals(b.id())&&a.get("server").equals(b.get("server"));}
    void trackMenu(Track t){new AlertDialog.Builder(this).setTitle(t.title()).setItems(new String[]{"下一首播放","歌曲信息"},(d,n)->{if(n==0){if(deck!=null){deck.insert(t);toast("已加入下一首");}}else showTrackInfo(t);}).show();}
    void showTrackInfo(Track t){new AlertDialog.Builder(this).setTitle("歌曲信息").setMessage("歌名："+t.title()+"\n歌手："+t.get("artist")+"\n专辑："+t.get("album")+"\n来源："+(t.remote()?"服务器曲库":"本地音乐")+(t.remote()?"":"\n文件："+t.get("file"))).setPositiveButton("关闭",null).show();}
    void pick(){try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra("android.content.extra.SHOW_ADVANCED",true).putExtra("android.provider.extra.SHOW_ADVANCED",true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),1);}catch(ActivityNotFoundException e){
        if(checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},90);toast("授权后请重新选择文件夹");return;}
        FolderPicker.open(this);
    }catch(Exception e){toast("无法打开系统文件夹选择器");}}
    private static final int EXPORT_LOG=2;
    boolean logsExpanded;
    void settings(){
        LinearLayout h=header("设置与日志");
        action(h,"组件许可",()->{try{String notice=new String(Library.bytes(getAssets().open("licenses/NOTICE.txt"),32768),"UTF-8");new AlertDialog.Builder(this).setTitle("组件许可 · BASS").setMessage(notice).setPositiveButton("关闭",null).show();}catch(Exception ex){toast("许可文件读取失败");}});
        LinearLayout sound=col();pad(sound,12,8);sound.setBackground(shape(surface,12));content.addView(sound,new LinearLayout.LayoutParams(-1,-2));sound.addView(label("播放",13,MUTED));
        Switch gain=new Switch(this);gain.setText("ReplayGain · 单曲音量均衡");gain.setTextColor(INK);gain.setMinHeight(dp(48));gain.setChecked(getSharedPreferences("deck",0).getBoolean("replayGain",false));sound.addView(gain);
        gain.setOnCheckedChangeListener((button,enabled)->{getSharedPreferences("deck",0).edit().putBoolean("replayGain",enabled).apply();if(deck!=null)deck.refreshGain();});
        Space gap=new Space(this);content.addView(gap,new LinearLayout.LayoutParams(1,dp(12)));
        LinearLayout logTools=row();logTools.setBackground(shape(surface,12));content.addView(logTools);TextView title=label("运行日志",14,INK);pad(title,12,0);fill(logTools,title);
        action(logTools,logsExpanded?"收起":"查看",()->{logsExpanded=!logsExpanded;render();});
        if(logsExpanded)action(logTools,"刷新",this::render);
        action(logTools,"导出日志",this::pickLogDestination);
        if(!logsExpanded)return;
        ScrollView scroll=new ScrollView(this);fill(content,scroll);
        TextView preview=label("读取中…",14,INK);pad(preview,6,8);preview.setTextIsSelectable(true);scroll.addView(preview);
        Journal.read(this,(text,ok)->ui(()->{
            if(!preview.isAttachedToWindow())return;
            if(!ok){preview.setText("日志读取失败，请重试");return;}
            int start=text.length();
            for(int n=0;n<120 && start>0;n++){start=text.lastIndexOf('\n',start-2)+1;}
            preview.setText(text.substring(start));scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
        }));
    }
    void pickLogDestination(){
        String stamp=new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date());
        Intent picker=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE,"轻椒音乐-运行日志-"+stamp+".txt")
                .putExtra("android.content.extra.SHOW_ADVANCED",true);
        try{startActivityForResult(picker,EXPORT_LOG);}
        catch(ActivityNotFoundException e){toast("车机缺少可保存文件的系统文件选择器");}
    }
    void exportLog(Uri destination){
        Journal.read(this,(text,ok)->ui(()->{
            if(!ok){toast("无法读取日志");return;}
            io.execute(()->{
                try{
                    SourceAccess.writeLog(this,destination,text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }catch(Exception e){
                    Journal.event(this,"导出失败 · "+e.getClass().getSimpleName());
                    ui(()->toast("写入失败，请检查目标目录后重试"));return;
                }
                Journal.event(this,"日志已导出");ui(()->{toast("日志已保存到所选位置");if("设置".equals(page))render();});
            });
        }));
    }
    protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);
        if(code==EXPORT_LOG){if(result==RESULT_OK&&data!=null&&data.getData()!=null)exportLog(data.getData());return;}
        if(code==1&&result==RESULT_OK&&data!=null&&data.getData()!=null){
        try{Uri tree=data.getData();getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION);scan(tree);}catch(Exception e){Journal.event(this,"目录授权失败 · "+e.getClass().getSimpleName());toast("无法保存文件夹读取授权");}}}
    Set<String> folders(){
        Set<String> saved=getPreferences(0).getStringSet("folders",null);
        if(saved!=null)return new TreeSet<>(saved);
        Set<String> result=new TreeSet<>();String old=getPreferences(0).getString("tree","");
        if(!old.isEmpty())result.add(old);return result;
    }
    void saveFolders(Set<String> dirs){getPreferences(0).edit().putStringSet("folders",new HashSet<>(dirs)).apply();}
    void folderPage(){
        LinearLayout h=header("扫描音乐");action(h,scanning?"扫描中":"添加文件夹",()->{if(!scanning)pick();});
        ScrollView scroll=new ScrollView(this);fill(content,scroll);LinearLayout rows=col();scroll.addView(rows);
        if(folders().isEmpty())rows.addView(label("尚未选择文件夹",15,MUTED));
        for(String dir:folders()){
            LinearLayout line=row();rows.addView(line);Uri uri=Uri.parse(dir);
            String path="file".equals(uri.getScheme())?uri.getPath():android.provider.DocumentsContract.getTreeDocumentId(uri);
            fill(line,label(path,15,INK));action(line,"扫描",()->scan(uri));
            action(line,"移除",()->{if(!scanning)new AlertDialog.Builder(this).setMessage("从曲库移除 "+path+"？音乐文件会保留。").setPositiveButton("移除",(d,w)->removeFolder(dir)).setNegativeButton("取消",null).show();});
        }
    }
    boolean belongs(Track t,String dir){
        return dir.equals(t.get("tree"))||t.id().startsWith(dir+"/document/");
    }
    void removeFolder(String dir){
        if(scanning)return;
        ArrayList<Track> kept=new ArrayList<>();for(Track t:local)if(!belongs(t,dir))kept.add(t);
        try{Library.save(this,"library",Track.pack(kept));Set<String> dirs=folders();dirs.remove(dir);saveFolders(dirs);
            local.clear();local.addAll(kept);render();
        }catch(Exception e){toast("目录移除失败");}
    }
    void scan(Uri tree){
        if(scanning||!libraryReady)return;final int scanToken=scanSerial.incrementAndGet();String dir=tree.toString();Set<String> dirs=folders();dirs.add(dir);saveFolders(dirs);
        ArrayList<Track> previous=new ArrayList<>(),kept=new ArrayList<>();
        for(Track t:local){if(belongs(t,dir))previous.add(t);else kept.add(t);}
        scanning=true;render();io.execute(()->{try{
            kept.addAll(Library.scan(this,tree,previous));synchronized(scanLock){if(scanToken!=scanSerial.get()||Thread.currentThread().isInterrupted())return;Library.save(this,"library",Track.pack(kept));}
            ui(()->{local.clear();local.addAll(kept);scanning=false;render();toast("扫描完成");});
        }catch(Exception e){if(scanToken!=scanSerial.get())return;Journal.event(this,"扫描失败 · "+e.getClass().getSimpleName());ui(()->{scanning=false;render();
            if(e instanceof Library.EmptyFolder)new AlertDialog.Builder(this).setMessage("目录读取成功但没有音乐。请确认 U 盘已就绪；是否清理该目录的旧歌曲记录？").setNegativeButton("保留",null).setPositiveButton("清理记录",(d,w)->clearEmptyFolder(dir,scanToken)).show();
            else toast("目录暂不可读或保存失败，原曲库已保留");});}});
    }
    void clearEmptyFolder(String dir,int token){if(scanning||token!=scanSerial.get())return;ArrayList<Track> kept=new ArrayList<>();for(Track t:local)if(!belongs(t,dir))kept.add(t);scanning=true;io.execute(()->{try{synchronized(scanLock){if(token!=scanSerial.get())return;Library.save(this,"library",Track.pack(kept));}ui(()->{local.clear();local.addAll(kept);scanning=false;render();});}catch(Exception e){ui(()->{scanning=false;toast("清理未保存，原记录已保留");render();});}});}
    void ui(Runnable fn){runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())fn.run();});}
    void askSearch(boolean online){EditText field=new EditText(this);field.setSingleLine();field.setHint("歌曲或歌手");field.setText(online?serverSearch:query);
        new AlertDialog.Builder(this).setTitle(online?"搜索服务器曲库":"搜索本地曲库").setView(field).setPositiveButton("搜索",(d,w)->{String q=field.getText().toString().trim();
        if(online){serverSearch=q;render();}
        else{query=q;render();}}).setNegativeButton("取消",null).show();}
    boolean catalogLoaded;
    void serverPage(){
        LinearLayout h=header("服务器曲库");
        icon(h,"search","搜索服务器歌曲",INK,()->askSearch(true));
        action(h,hasAccount()?"账号":"连接",this::account);
        if(!hasAccount())return;
        ArrayList<Track> matches=new ArrayList<>();
        String key=serverSearch.toLowerCase(Locale.ROOT);
        for(Track track:remote)if((track.title()+detail(track)).toLowerCase(Locale.ROOT).contains(key))matches.add(track);
        sortTracks(matches);
        songTools(matches);
        LinearLayout operations=row();content.addView(operations);
        if(!serverSearch.isEmpty())action(operations,"清除搜索",()->{serverSearch="";render();});
        action(operations,catalogComplete?"刷新曲库":"补全曲库",()->{if(!remoteBusy)loadAlbums(0);});
        if(pendingCatalog!=null)action(operations,"重试保存",this::retryCatalogSave);
        if(!serverNote.isEmpty())content.addView(label(serverNote,13,MUTED));
        songs(matches);
    }
    JSONObject callServer(Server server,String operation,String... params) throws Exception {
        try {
            JSONObject reply=server.call(operation,params);
            Journal.event(this,"Subsonic "+operation+" 成功");
            return reply;
        } catch(Exception ex) {
            String reason=ex instanceof Server.ReplyError?ex.getMessage():ex.getClass().getSimpleName();
            Journal.event(this,"Subsonic "+operation+" 失败 · "+reason);
            throw ex;
        }
    }
    void account(){LinearLayout fields=col();pad(fields,18,0);EditText addr=new EditText(this),user=new EditText(this),pw=new EditText(this);
        SharedPreferences p=getSharedPreferences("account",0);addr.setSingleLine();user.setSingleLine();addr.setHint("https://服务器地址");addr.setText(p.getString("base",""));user.setHint("用户名");user.setText(p.getString("user",""));pw.setSingleLine();pw.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);pw.setHint("密码");fields.addView(addr);fields.addView(user);fields.addView(pw);
        new AlertDialog.Builder(this).setTitle("连接 Subsonic").setView(fields).setNegativeButton("取消",null).setPositiveButton("验证并保存",(d,n)->{
            String a=addr.getText().toString(),u=user.getText().toString(),pass=pw.getText().toString();int token=++request;serverNote="正在验证账号…";remoteBusy=true;render();
            io.execute(()->{try{Server s=new Server(a,u,pass);callServer(s,"ping");ui(()->{if(token!=request)return;try{s.save(this);getSharedPreferences("account",0).edit().remove("catalogOwner").apply();albums=new JSONArray();pendingCatalog=null;remote.clear();serverSearch="";loadAlbums(0);}catch(Exception e){serverError(token,e);}});}catch(Exception e){ui(()->serverError(token,e));}});}).show();}
    void serverError(int token,Exception e){if(token!=request)return;remoteBusy=false;serverNote=networkError(e)+"；已有歌曲已保留，可重试读取"+(pendingCatalog!=null?"；部分数据尚未保存，请重试保存":"");if(page.equals("服务器"))render();}
    JSONArray albumBatch(Server server,int offset)throws Exception{
        try{return callServer(server,"getAlbumList2","type","alphabeticalByName","size","100","offset",String.valueOf(offset)).getJSONObject("albumList2").optJSONArray("album");}
        catch(Server.ReplyError ex){if(!ex.getMessage().equals("HTTP 404")&&!ex.getMessage().equals("HTTP 501"))throw ex;
            return callServer(server,"getAlbumList","type","alphabeticalByName","size","100","offset",String.valueOf(offset)).getJSONObject("albumList").optJSONArray("album");}
    }
    String networkError(Exception e){if(e instanceof java.net.UnknownHostException)return "服务器域名无法解析";if(e instanceof java.net.SocketTimeoutException)return "服务器响应超时";if(e instanceof java.net.ConnectException)return "无法连接服务器";if(e instanceof javax.net.ssl.SSLException)return "安全连接失败";if(e instanceof Server.ReplyError)return "服务器返回 "+e.getMessage();return "连接失败 · "+e.getClass().getSimpleName();}
    void retryCatalogSave(){if(pendingCatalog==null||remoteBusy)return;ArrayList<Track> songs=new ArrayList<>(pendingCatalog);String owner=pendingOwner;boolean complete=pendingComplete;int token=request;remoteBusy=true;io.execute(()->{try{CatalogStore.save(this,owner,songs,complete);ui(()->{if(token!=request)return;pendingCatalog=null;remoteBusy=false;serverNote=complete?"曲库已保存":"已保存，曲库尚未完整，可点击补全";render();});}catch(Exception e){ui(()->{if(token!=request)return;remoteBusy=false;serverNote="缓存仍无法保存，原缓存已保留";render();});}});}
    void loadAlbums(int ignored){
        final ArrayList<Track> previous=new ArrayList<>(remote);
        int token=++request;catalogLoaded=true;remoteBusy=true;serverNote="正在读取歌曲…";render();
        io.execute(()->{try{
            Server server=Server.load(this);
            LinkedHashMap<String,Track> catalog=new LinkedHashMap<>();
            HashSet<String> seenAlbums=new HashSet<>();
            ArrayList<Track> additions=new ArrayList<>();
            long publishedAt=0,savedAt=0;boolean checkpointFailed=false;
            int failures=0;
            for(int offset=0;;){
                if(request!=token||Thread.currentThread().isInterrupted())return;
                JSONArray batch=albumBatch(server,offset);
                if(batch==null||batch.length()==0)break;
                int before=seenAlbums.size();
                for(int i=0;i<batch.length();i++){
                    if(request!=token||Thread.currentThread().isInterrupted())return;
                    JSONObject album=batch.optJSONObject(i);
                    String id=album==null?"":album.optString("id","");
                    if(id.isEmpty()){failures++;continue;}
                    if(!seenAlbums.add(id))continue;
                    try{
                        JSONArray songs=callServer(server,"getAlbum","id",id).getJSONObject("album").optJSONArray("song");
                        for(Track track:server.tracks(songs)){
                            if(catalog.put(track.id(),track)==null)additions.add(track);
                        }
                    }catch(Exception ex){failures++;continue;}
                    long now=android.os.SystemClock.elapsedRealtime();
                    if(!catalog.isEmpty()&&(savedAt==0||now-savedAt>=1500)){
                        LinkedHashMap<String,Track> checkpoint=new LinkedHashMap<>();
                        for(Track old:previous)if(server.account().equals(old.get("server")))checkpoint.put(old.id(),old);
                        checkpoint.putAll(catalog);
                        try{CatalogStore.save(this,server.account(),checkpoint.values(),false);savedAt=now;checkpointFailed=false;}
                        catch(Exception ex){checkpointFailed=true;Journal.event(this,"服务器曲库分批保存失败 · "+ex.getClass().getSimpleName());ui(()->{if(token==request){pendingCatalog=new ArrayList<>(checkpoint.values());pendingOwner=server.account();pendingComplete=false;serverNote="读取中，但缓存保存失败；数据暂未保存";if(page.equals("服务器"))render();}});}
                    }
                    if(previous.isEmpty()&&!additions.isEmpty()&&(publishedAt==0||now-publishedAt>=1500)){
                        ArrayList<Track> chunk=new ArrayList<>(additions);
                        boolean first=publishedAt==0;final boolean unsaved=checkpointFailed;
                        ui(()->{if(token!=request)return;if(first)remote.clear();remote.addAll(chunk);
                            serverNote="已读取 "+remote.size()+" 首…"+(unsaved?" 缓存保存失败，尚未保存":"");if(page.equals("服务器"))render();});
                        additions.clear();publishedAt=now;
                    }
                }
                if(batch.length()<100||seenAlbums.size()==before)break;
                offset+=batch.length();
            }
            if(request!=token||Thread.currentThread().isInterrupted())return;
            if(failures>0)for(Track old:previous)if(!catalog.containsKey(old.id()))catalog.put(old.id(),old);
            boolean saved=false;
            if(failures==0||!catalog.isEmpty()){
                try{CatalogStore.save(this,server.account(),catalog.values(),failures==0);saved=true;
                    Journal.event(this,"服务器曲库已保存 "+catalog.size()+" 首");
                }catch(Exception ex){Journal.event(this,"服务器曲库缓存写入失败");}
            }
            final int skipped=failures;final boolean persisted=saved;
            ui(()->{if(token!=request)return;
                if(skipped==0||!catalog.isEmpty()){remote.clear();remote.addAll(catalog.values());}
                catalogLoaded=true;catalogComplete=skipped==0;remoteBusy=false;
                pendingCatalog=persisted?null:new ArrayList<>(catalog.values());pendingOwner=server.account();pendingComplete=catalogComplete;
                serverNote=skipped>0?skipped+" 张专辑未读到，可刷新重试":remote.isEmpty()?"暂无歌曲":"";
                if(!persisted)serverNote="缓存保存失败，当前歌曲尚未保存；请重试保存。 "+serverNote;
                if(page.equals("服务器"))render();});
        }catch(Exception ex){ui(()->serverError(token,ex));}});
    }
    void player(){
        final int white=INK,muted=MUTED;
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,dark?new int[]{0xff29364c,0xff202b40,0xff28324a}:new int[]{PAPER,0xfff0eaf5,0xffeeeaf6}));
        Track t=deck==null?null:deck.current();rendered=t;
        LinearLayout top=row();root.addView(top);icon(top,"back","返回曲库",white,()->go(returnPage));
        Space spacer=new Space(this);fill(top,spacer);
        if(t==null){root.addView(label("请先选择歌曲",18,white));return;}
        LinearLayout panel=row();pad(panel,0,0);root.addView(panel,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout left=col();left.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(left,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout identity=row();identity.setGravity(Gravity.CENTER);pad(identity,24,4);
        left.addView(identity,new LinearLayout.LayoutParams(-1,dp(240)));
        FrameLayout cover=new FrameLayout(this);
        identity.addView(cover,new LinearLayout.LayoutParams(dp(248),-1));
        LinearLayout metadata=col();pad(metadata,12,0);metadata.setGravity(Gravity.CENTER_VERTICAL);identity.addView(metadata,new LinearLayout.LayoutParams(-2,-1));
        art=new ImageView(this){
            private final Path circle=new Path();
            @Override public void draw(Canvas canvas){
                int saved=canvas.save();circle.reset();
                circle.addCircle(getWidth()/2f,getHeight()/2f,Math.min(getWidth(),getHeight())/2f,Path.Direction.CW);
                canvas.clipPath(circle);super.draw(canvas);canvas.restoreToCount(saved);
            }
        };
        art.setBackgroundColor(Color.TRANSPARENT);
        art.setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View v,Outline outline){outline.setOval(0,0,v.getWidth(),v.getHeight());}});art.setClipToOutline(true);
        art.setImageDrawable(new Glyph("record",0xff171b24));
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        FrameLayout.LayoutParams artParams=new FrameLayout.LayoutParams(1,1,Gravity.CENTER);
        cover.addView(art,artParams);art.setContentDescription("圆形专辑封面");
        cover.addOnLayoutChangeListener((v,l,tt,r,b,ol,ot,or,ob)->{
            int size=Math.max(1,Math.min(r-l,b-tt)-dp(12));
            if(artParams.width!=size){
                artParams.width=size;artParams.height=size;
                cover.getChildAt(0).setLayoutParams(artParams);
            }
        });
        ScrollView controlScroll=new ScrollView(this);
        controlScroll.setFillViewport(true);
        LinearLayout.LayoutParams controlParams=new LinearLayout.LayoutParams(-1,-2);controlParams.topMargin=dp(20);left.addView(controlScroll,controlParams);
        LinearLayout info=col();pad(info,30,4);
        info.setGravity(Gravity.CENTER_VERTICAL);
        controlScroll.addView(info);
        lyricScroll=new ScrollView(this);
        lyricScroll.setFillViewport(true);
        panel.addView(lyricScroll,new LinearLayout.LayoutParams(0,-1,1));
        lyricsText=label("暂无歌词",19,muted);pad(lyricsText,12,16);
        lyricsText.setGravity(Gravity.CENTER);lyricScroll.addView(lyricsText);
        activeLyric=null;
        TextView title=label(t.title(),24,white);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);metadata.addView(title);
        TextView artist=label(t.get("artist").isEmpty()?"未知歌手":t.get("artist"),13,muted);artist.setMaxLines(2);metadata.addView(artist);
        TextView album=label(t.get("album"),12,muted);album.setMaxLines(2);album.setEllipsize(TextUtils.TruncateAt.END);metadata.addView(album);
        left.addOnLayoutChangeListener((v,l,y,r,b,ol,oy,or,ob)->{
            int available=b-y;
            int size=Math.max(dp(64),Math.min((int)((r-l)*0.39f),(int)(available*0.38f)));
            int height=Math.max(dp(64),Math.min(size+dp(12),available-controlScroll.getHeight()-dp(28)));
            int textWidth=Math.max(dp(60),(r-l)-dp(72)-size);
            if(title.getMaxWidth()!=textWidth)title.setMaxWidth(textWidth);
            if(artist.getMaxWidth()!=textWidth)artist.setMaxWidth(textWidth);
            if(album.getMaxWidth()!=textWidth)album.setMaxWidth(textWidth);
            if(identity.getLayoutParams().height!=height){identity.getLayoutParams().height=height;identity.requestLayout();}
            if(cover.getLayoutParams().width!=size){cover.getLayoutParams().width=size;cover.requestLayout();}
        });
        seek=new SeekBar(this);seek.setContentDescription("播放进度");seek.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));seek.setThumbTintList(android.content.res.ColorStateList.valueOf(white));info.addView(seek,new LinearLayout.LayoutParams(-1,dp(48)));
        clockText=label("",12,muted);clockText.setSingleLine();clockText.setEllipsize(TextUtils.TruncateAt.END);info.addView(clockText);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){remoteSeeking=false;seeking=true;}public void onStopTrackingTouch(SeekBar s){seeking=false;if(deck!=null)deck.seek(s.getProgress());}public void onProgressChanged(SeekBar s,int p,boolean user){if(user&&deck!=null)setChanged(clockText,time(p)+" / "+time(deck.duration())+" · 松手定位");}});
        seek.setOnKeyListener((v,key,event)->{
            if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){
                if(event.getAction()==KeyEvent.ACTION_DOWN&&deck!=null&&deck.duration()>0){
                    int position=remoteSeeking?seek.getProgress():deck.position();remoteSeeking=true;seeking=true;
                    seek.setProgress((int)Math.max(0,Math.min((long)deck.duration(),(long)position+(key==KeyEvent.KEYCODE_DPAD_RIGHT?10000:-10000))));
                    setChanged(clockText,time(seek.getProgress())+" / "+time(deck.duration())+" · 确认定位，返回取消");
                }return true;
            }
            if((key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER)&&remoteSeeking){if(event.getAction()==KeyEvent.ACTION_UP){int position=seek.getProgress();remoteSeeking=false;seeking=false;if(deck!=null)deck.seek(position);update();}return true;}return false;
        });
        seek.setOnFocusChangeListener((v,focused)->{if(!focused&&remoteSeeking){remoteSeeking=false;seeking=false;update();}});
        LinearLayout controls=row();controls.setGravity(Gravity.CENTER);info.addView(controls);
        ImageView modeButton=icon(controls,"shuffle","循环模式",muted,()->{});updateModeButton(modeButton);modeButton.setOnClickListener(v->{deck.mode=(deck.mode+1)%3;deck.saveMark();updateModeButton(modeButton);toast(modeButton.getContentDescription().toString());});
        pad(icon(controls,"previous","上一首",white,()->deck.step(-1,false)),6,6);
        playIcon=icon(controls,"play","播放",white,()->deck.toggle());playIcon.setLayoutParams(new LinearLayout.LayoutParams(dp(72),dp(64)));pad(playIcon,18,14);
        pad(icon(controls,"next","下一首",white,()->deck.step(1,false)),6,6);
        pad(icon(controls,"info","歌曲信息",muted,()->showTrackInfo(t)),10,10);
        Space bottom=new Space(this);root.addView(bottom,new LinearLayout.LayoutParams(1,dp(8)));artworkAndLyrics(t);
    }
    void updateModeButton(ImageView button){button.setImageDrawable(new Glyph(deck.mode==1?"shuffle":deck.mode==2?"repeatOne":"repeat",MUTED));button.setContentDescription(new String[]{"列表循环","随机播放","单曲循环"}[deck.mode]);}
    void artworkAndLyrics(Track t){lines.clear();boolean lyric=page.equals("歌词");ImageView target=art;TextView words=lyricsText;
        io.execute(()->{try{Bitmap cover=readCover(t,384);if(cover!=null)ui(()->{if(art==target&&target!=null)target.setImageBitmap(cover);});}catch(Exception ignored){}});
        io.execute(()->{try{
            if(lyric){byte[] bytes;
                if(!t.get("lrc").isEmpty())bytes=Library.bytes(SourceAccess.open(this,Uri.parse(t.get("lrc"))),1024*1024);
                else if(t.remote())bytes=BlobCache.get(this,"lyrics-v2|"+mediaKey(t)+"|"+(System.currentTimeMillis()/3600000),1024*1024,()->{
                    Server server=Server.load(this);if(!server.account().equals(t.get("server")))return null;
                    try{String structured=Lyrics.structured(callServer(server,"getLyricsBySongId","id",t.id()));if(!structured.trim().isEmpty())return structured.getBytes("UTF-8");}catch(Server.ReplyError unsupported){Journal.event(this,"同步歌词接口不可用，尝试传统歌词");}
                    JSONObject result=callServer(server,"getLyrics","artist",t.get("artist"),"title",t.title()).optJSONObject("lyrics");return (result==null?"":result.optString("value")).getBytes("UTF-8");
                });else bytes=new byte[0];
                String text=Lyrics.decode(bytes);TreeMap<Integer,String> parsed=Lyrics.parse(text);
                String plain=text.trim();ui(()->{if(lyricsText!=words)return;lines.putAll(parsed);if(parsed.isEmpty()&&!plain.isEmpty()){words.setText(plain);words.setMovementMethod(android.text.method.ScrollingMovementMethod.getInstance());}update();});return;}
            Bitmap bmp=readCover(t,640);
            if(bmp!=null)ui(()->{if(art==target&&target!=null)target.setImageBitmap(bmp);});
        }catch(Exception ignored){}});}
    void changed(){if(deck==null||!uiActive)return;if((page.equals("播放")||page.equals("歌词"))&&!same(rendered,deck.current()))render();else update();if(!deck.message.isEmpty()&&!deck.message.equals(lastPlaybackMessage))toast(deck.message);lastPlaybackMessage=deck.message;timer.removeCallbacks(tick);if(deck.want)timer.postDelayed(tick,700);}
    String time(int ms){return String.format(Locale.ROOT,"%d:%02d",ms/60000,(ms/1000)%60);}
    void setChanged(TextView v,String value){if(v!=null&&!TextUtils.equals(v.getText(),value))v.setText(value);}
    void update(){Track t=deck==null?null:deck.current();if(miniCover!=null)thumbnail(miniCover,t);setChanged(mini,t==null?"尚未播放":t.title());setChanged(miniDetail,t==null?"选择歌曲开始播放":deck.playbackStatus()+" · "+detail(t));setChanged(play,deck!=null&&deck.want?"暂停":"播放");
        if(playIcon!=null){boolean active=deck!=null&&deck.want;playIcon.setImageDrawable(new Glyph(active?"pause":"play",INK));playIcon.setContentDescription(active?"暂停":"播放");}
        if(deck!=null&&seek!=null){int pos=deck.position();if(!seeking){seek.setEnabled(deck.duration()>0);seek.setMax(Math.max(1,deck.duration()));seek.setProgress(deck.duration()>0?pos:0);setChanged(clockText,time(pos)+" / "+(deck.duration()>0?time(deck.duration()):"--:--")+" · "+deck.playbackStatus());}
            if(lyricsText!=null&&!lines.isEmpty()){Map.Entry<Integer,String> cur=lines.floorEntry(pos);highlightLyric(cur==null?-1:cur.getKey());}}}
    ScrollView lyricScroll;
    Integer activeLyric;
    void highlightLyric(int stamp){
        if(activeLyric!=null&&activeLyric==stamp)return;
        activeLyric=stamp;android.text.SpannableStringBuilder text=new android.text.SpannableStringBuilder();int offset=0;
        for(Map.Entry<Integer,String> line:lines.entrySet()){
            int start=text.length();text.append(line.getValue());
            if(line.getKey()==stamp){offset=start;text.setSpan(new android.text.style.ForegroundColorSpan(ACCENT),start,text.length(),33);
                text.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),start,text.length(),33);}
            text.append("\n\n");
        }
        TextView target=lyricsText;ScrollView scroll=lyricScroll;target.setText(text);final int currentOffset=offset;
        target.post(()->{android.text.Layout layout=target.getLayout();if(layout!=null&&target==lyricsText){
            int y=layout.getLineTop(layout.getLineForOffset(currentOffset))+target.getTotalPaddingTop();
            scroll.smoothScrollTo(0,Math.max(0,y-scroll.getHeight()/2));}});
    }
    final Runnable tick=new Runnable(){public void run(){if(!uiActive)return;update();if(deck!=null&&deck.want)timer.postDelayed(this,700);}};
    @Override public boolean dispatchTouchEvent(MotionEvent event){if(event.getAction()==MotionEvent.ACTION_DOWN)remoteUi.touch();return super.dispatchTouchEvent(event);}
    @Override public boolean dispatchKeyEvent(KeyEvent event){if(root!=null&&remoteUi.key(event))return true;return super.dispatchKeyEvent(event);}
    public void onBackPressed(){if(remoteSeeking){remoteSeeking=false;seeking=false;update();return;}if(selecting){selecting=false;chosen.clear();render();return;}if(page.equals("服务器")&&!serverSearch.isEmpty()){serverSearch="";render();return;}if(page.equals("歌词"))go(returnPage);else if(page.equals("服务器")&&!remoteTitle.isEmpty())loadAlbums(albumOffset);else if(!page.equals("本地"))go("本地");else if(!query.isEmpty()){query="";render();}else if(!group.isEmpty()){group="";render();}else super.onBackPressed();}
}




