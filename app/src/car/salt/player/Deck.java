package car.salt.player;

import android.app.*;
import android.content.*;
import android.media.*;
import android.media.session.*;
import android.net.Uri;
import android.os.*;
import java.util.*;
import org.json.*;

public final class Deck extends Service {
    final ArrayList<Track> queue=new ArrayList<>();
    int index,mode=1,mark;boolean restored;String revision=UUID.randomUUID().toString();
    private static final java.util.concurrent.ExecutorService writer=java.util.concurrent.Executors.newSingleThreadExecutor();
    boolean ready,want;
    String playbackProblem="";
    String message="";
    private SoftPlayer audio;
    final ArrayDeque<Integer> explicitNext=new ArrayDeque<>(),history=new ArrayDeque<>();
    private AudioManager audioManager;
    private boolean hasFocus,focusPaused,ducked,destroyed;
    private final AudioManager.OnAudioFocusChangeListener focusListener=change->this.clock.post(()->focusChanged(change));
    private final BroadcastReceiver noisy=new BroadcastReceiver(){public void onReceive(Context c,Intent i){if(AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction()))pauseTrack();}};

    private final Map<String,JSONObject> gainCache=new HashMap<>();
    double appliedGainDb;
    private final java.util.concurrent.ExecutorService reader=java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile int generation;
    private java.io.File cached;
    private boolean copied;
    private boolean loading;
    private Runnable prepareTimeout;
    private long waitForMountUntil;
    private final BroadcastReceiver storage=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
        if(SystemClock.elapsedRealtime()<waitForMountUntil&&current()!=null&&!current().remote()){
            waitForMountUntil=0;Journal.event(Deck.this,"存储挂载完成，重试原歌曲");resumeTrack();
        }
    }};
    private MediaSession session;
    private final Handler clock=new Handler();
    Runnable change;
    private final Random random=new Random();
    private final Runnable checkpoint=new Runnable(){public void run(){if(ready&&playing())saveMark();clock.postDelayed(this,3000);}};
    final class Link extends Binder {Deck service(){return Deck.this;}}
    public IBinder onBind(Intent i){return new Link();}
    SharedPreferences prefs(){return getSharedPreferences("deck",0);}
    public void onCreate(){super.onCreate();
        audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);
        registerReceiver(noisy,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        IntentFilter filter=new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);filter.addDataScheme("file");registerReceiver(storage,filter);
        java.io.File[] stale=getCacheDir().listFiles((dir,name)->(name.startsWith("playing-")||name.startsWith("softnet-")));if(stale!=null)for(java.io.File file:stale)file.delete();
        writer.execute(()->{ArrayList<Track> loaded=new ArrayList<>();int selected=0,position=0,playMode=1;String rev=UUID.randomUUID().toString(),error="";JSONArray next=new JSONArray(),past=new JSONArray();
            try{byte[] bytes=Store.read(this,"deck-snapshot");
                if(bytes==null){loaded.addAll(Track.unpack(Library.load(this,"queue")));selected=prefs().getInt("index",0);position=prefs().getInt("mark",0);playMode=prefs().getInt("mode",1);}
                else{JSONObject state=new JSONObject(new String(bytes,"UTF-8"));loaded.addAll(Track.unpack(state.getJSONArray("queue")));rev=state.getString("revision");selected=state.optInt("index");position=state.optInt("mark");playMode=state.optInt("mode",1);next=state.optJSONArray("next");past=state.optJSONArray("history");
                    byte[] checkpoint=Store.read(this,"deck-position");if(checkpoint!=null){JSONObject saved=new JSONObject(new String(checkpoint,"UTF-8"));if(rev.equals(saved.optString("revision"))){selected=saved.optInt("index",selected);position=saved.optInt("mark",position);playMode=saved.optInt("mode",playMode);next=saved.optJSONArray("next");past=saved.optJSONArray("history");}}
                }
            }catch(Exception e){error="播放记录读取失败，已保留原文件";Journal.event(this,error+" · "+e.getClass().getSimpleName());}
            final int n=selected,p=position,m=playMode;final String r=rev,warning=error;final JSONArray pending=next,previous=past;
            clock.post(()->{if(destroyed)return;queue.addAll(loaded);index=Math.max(0,Math.min(n,queue.size()-1));mark=Math.max(0,p);mode=Math.max(0,Math.min(2,m));revision=r;restoreIndices(explicitNext,pending);restoreIndices(history,previous);restored=true;message=warning;Journal.event(this,"恢复播放队列 "+queue.size()+" 首，进度 "+mark+" ms");state();});
        });
        session=new MediaSession(this,"NewSaltCar");session.setFlags(3);
        session.setCallback(new MediaSession.Callback(){
            public void onPlay(){resumeTrack();}public void onPause(){pauseTrack();}public void onSkipToNext(){step(1,false);}
            public void onSkipToPrevious(){step(-1,false);}public void onSeekTo(long p){seek((int)p);}
            public void onStop(){pauseTrack();}
        });session.setActive(true);state();clock.postDelayed(checkpoint,3000);
    }
    public int onStartCommand(Intent i,int flags,int id){
        if(i!=null){String a=i.getAction();if("toggle".equals(a))toggle();if("next".equals(a))step(1,false);}
        return START_NOT_STICKY;
    }
    Track current(){return index>=0&&index<queue.size()?queue.get(index):null;}
    boolean playing(){return ready&&audio!=null&&audio.isPlaying();}
    int softwareFrames(){return audio==null?0:audio.decodedFrames();}
    int position(){return ready?audio.getCurrentPosition():mark;}
    int duration(){int actual=ready?Math.max(0,audio.getDuration()):0;return actual>0?actual:current()==null?0:Math.max(0,current().data.optInt("duration",0));}
    void restoreIndices(ArrayDeque<Integer> target,JSONArray values){if(values!=null)for(int i=0;i<values.length();i++){int value=values.optInt(i,-1);if(value>=0&&value<queue.size())target.add(value);}}
    JSONObject positionState()throws Exception{return new JSONObject().put("revision",revision).put("index",index).put("mark",mark).put("mode",mode).put("next",new JSONArray(explicitNext)).put("history",new JSONArray(history));}
    void seek(int p){mark=Math.max(0,p);if(ready)audio.seekTo(mark);persist(false);state();}
    void saveMark(){mark=position();persist(false);}
    void persist(boolean list){if(!restored)return;try{JSONObject snapshot=positionState();if(list)snapshot.put("queue",Track.pack(new ArrayList<>(queue)));writer.execute(()->{try{Store.save(this,list?"deck-snapshot":"deck-position",snapshot.toString().getBytes("UTF-8"));}catch(Exception e){Journal.event(this,"播放记录保存失败 · "+e.getClass().getSimpleName());clock.post(()->{if(!destroyed){message="播放记录尚未保存，请检查存储空间";state();}});}});}catch(Exception e){message="播放记录保存失败";}}
    void saveList(){revision=UUID.randomUUID().toString();persist(true);}
    void replace(List<Track> tracks,int selected){if(tracks.isEmpty()||!restored)return;release();queue.clear();queue.addAll(tracks);explicitNext.clear();history.clear();index=Math.max(0,Math.min(selected,queue.size()-1));mark=0;saveList();resumeTrack();}
    void shift(ArrayDeque<Integer> values,int at,int delta){ArrayDeque<Integer> copy=new ArrayDeque<>(values);values.clear();for(int n:copy){if(delta<0&&n==at)continue;values.add(n>=at?n+delta:n);}}
    void insert(Track t){if(!restored)return;if(queue.isEmpty()){queue.add(t);index=0;mark=0;}else{int at=index+1;shift(explicitNext,at,1);shift(history,at,1);queue.add(at,t);explicitNext.addFirst(at);}saveList();state();}
    void remove(int n){if(!restored||n<0||n>=queue.size())return;mark=position();boolean active=n==index;
        queue.remove(n);shift(explicitNext,n,-1);shift(history,n,-1);if(n<index)index--;if(active){release();mark=0;want=false;abandonFocus();index=Math.min(index,Math.max(0,queue.size()-1));stopForeground(true);}
        saveList();state();}
    void select(int n){if(!restored||n<0||n>=queue.size())return;if(n!=index){history.addLast(index);while(history.size()>256)history.removeFirst();}activate(n);}
    void activate(int n){release();index=n;mark=0;saveMark();resumeTrack();}
    void step(int delta,boolean finished){if(!restored||queue.isEmpty())return;
        if(delta<0&&!history.isEmpty()){activate(history.removeLast());return;}
        int next=(index+delta+queue.size())%queue.size();
        if(delta>0&&!explicitNext.isEmpty())next=explicitNext.removeFirst();
        else if(finished&&mode==2)next=index;
        else if(mode==1&&queue.size()>1&&delta>0)next=(index+1+random.nextInt(queue.size()-1))%queue.size();
        select(next);}
    void toggle(){if(want)pauseTrack();else resumeTrack();}
    void pauseTrack(){waitForMountUntil=0;want=false;if(audio!=null)audio.pause();saveMark();abandonFocus();Journal.event(this,"暂停播放，进度 "+mark+" ms");stopForeground(false);state();}
    private boolean takeFocus(){
        if(hasFocus&&!focusPaused)return true;
        if(audioManager.requestAudioFocus(focusListener,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)return false;
        hasFocus=true;focusPaused=false;ducked=false;return true;
    }
    private void abandonFocus(){hasFocus=false;focusPaused=false;ducked=false;audioManager.abandonAudioFocus(focusListener);if(audio!=null)audio.setDucking(false);}
    void focusChanged(int change){
        if(destroyed||(!hasFocus&&!want))return;
        if(change==AudioManager.AUDIOFOCUS_LOSS){hasFocus=false;pauseTrack();return;}
        if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT){focusPaused=true;if(audio!=null)audio.pause();saveMark();}
        else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK){ducked=true;if(audio!=null)audio.setDucking(true);}
        else if(change==AudioManager.AUDIOFOCUS_GAIN){hasFocus=true;focusPaused=false;ducked=false;if(audio!=null)audio.setDucking(false);if(want&&ready)audio.start();}
        state();
    }
    String playbackStatus(){if(!message.isEmpty())return message;if(current()==null)return "尚未选择歌曲";if(!want)return "已暂停";if(focusPaused)return "等待其他音频结束";if(!ready)return "正在读取音频…";return playing()?(ducked?"正在播放 · 暂时降低音量":"正在播放"):"正在缓冲…";}
    void resumeTrack(){if(!restored)return;Track track=current();if(track==null)return;
        if(!takeFocus()){want=false;message="其他应用正在使用音频，请稍后重试";state();return;}
        if(explicitNext.removeFirstOccurrence(index))persist(false);
        playbackProblem="";Journal.event(this,(track.remote()?"服务器":"本地")+"播放请求，队列第 "+(index+1)+" 首，标题="+track.title().replaceAll("[\\r\\n]"," "));
        want=true;message="";startService(new Intent(this,Deck.class));
        if(ready){audio.setDucking(ducked);audio.start();state();return;}if(audio!=null||loading){state();return;}
        prepare(track,null);
    }
    String gainKey(Track track){return track.id()+"|"+track.get("mtime")+"|"+track.get("size");}
    void prepare(Track track,java.io.File localCopy){
        if(prefs().getBoolean("replayGain",false)&&!track.remote()&&!gainCache.containsKey(gainKey(track))){
            loading=true;final int token=generation;prepareTimeout=()->{if(token==generation&&loading)failed(track,"标签读取超时");};clock.postDelayed(prepareTimeout,30000);state();
            reader.execute(()->{JSONObject tags=new JSONObject();try{tags=ReplayGain.read(this,Uri.parse(track.id()));}catch(Exception ex){Journal.event(this,"ReplayGain 预读失败 · "+ex.getClass().getSimpleName());}
                final JSONObject value=tags;clock.post(()->{if(token!=generation)return;if(prepareTimeout!=null)clock.removeCallbacks(prepareTimeout);loading=false;if(gainCache.size()>256)gainCache.clear();gainCache.put(gainKey(track),value);prepare(track,localCopy);});});return;
        }
        try{SoftPlayer player=new SoftPlayer(this);audio=player;
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setWakeMode(this,PowerManager.PARTIAL_WAKE_LOCK);
            player.setOnPreparedListener(p->{if(audio!=p)return;if(prepareTimeout!=null)clock.removeCallbacks(prepareTimeout);ready=true;if(localCopy!=null)Journal.event(this,"兼容路径播放成功：原路径读取或解码兼容问题已绕过");try{if(p.getDuration()>0){track.data.put("duration",p.getDuration());saveList();}}catch(Exception ignored){}refreshGain();p.setDucking(ducked);Journal.event(this,"音频已就绪，时长 "+p.getDuration()+" ms");if(mark>0)p.seekTo(mark);if(want&&!focusPaused)p.start();else p.pause();state();});
            player.setOnCompletionListener(p->{if(p==audio)step(1,true);});
            player.setOnErrorListener((p,w,x)->{if(p==audio){playbackProblem=p.failureReason();Journal.event(this,"播放失败分类="+playbackProblem+"，阶段=prepare/play，输入="+(localCopy==null?"原始来源":"已复制本地文件")+"，what="+w+" extra="+x);fallback(track);}return true;});
            if(track.remote())player.setDataSource(Server.load(this).stream(track));
            else if(localCopy!=null)player.setDataSource(localCopy.getAbsolutePath());
            else {Exception last=null;boolean opened=false;
                for(Uri source:SourceAccess.candidates(this,Uri.parse(track.id()))){try{player.setDataSource(this,source);opened=true;break;}catch(Exception ex){last=ex;Journal.event(this,"数据源候选失败 scheme="+source.getScheme()+" provider="+source.getAuthority()+"，异常="+ex.getClass().getSimpleName());}}
                if(!opened)throw new java.io.IOException("文件读取失败",last);
            }
            prepareTimeout=()->{if(audio==player&&!ready)failed(track,"读取或播放准备超时");};clock.postDelayed(prepareTimeout,30000);
            player.prepareAsync();state();
        }catch(LinkageError e){Journal.event(this,"软解库加载失败 · "+e.getClass().getSimpleName());failed(track,"软解库与设备架构不兼容");}catch(Exception e){playbackProblem=PlaybackFailure.exception(e,"打开数据源");Journal.event(this,"播放失败分类="+playbackProblem+"，阶段=打开数据源，异常="+e.getClass().getSimpleName());fallback(track);}
    }
    void fallback(Track track){
        if(track.remote()||copied){failed(track,playbackProblem.isEmpty()?"播放失败，原因尚未确定，请查看日志":playbackProblem);return;}
        if(ready)try{saveMark();}catch(Exception ignored){}
        release();copied=true;loading=true;final int token=generation;
        message="正在兼容读取文件…";state();
        reader.execute(()->{
            String stage="兼容文件复制";
            try{
                java.io.File original=SourceAccess.cache(this,Uri.parse(track.id()),()->token!=generation);
                Journal.event(this,"文件读取验证成功：已复制 "+original.length()+" 字节，开始检查解码兼容性");
                final java.io.File file=original;
                clock.post(()->{if(token!=generation){file.delete();return;}loading=false;cached=file;message="";Journal.event(this,"使用兼容文件读取");prepare(track,file);});
            }catch(Exception ex){final String failedStage=stage;final String reason=PlaybackFailure.exception(ex,failedStage);clock.post(()->{if(token==generation){Journal.event(this,"播放失败分类="+reason+"，阶段="+failedStage+"，异常="+ex.getClass().getSimpleName());failed(track,reason);}});}
        });
    }
    void failed(Track t){failed(t,"文件暂不可读，请检查存储授权或等待U盘挂载");}
    void failed(Track t,String reason){if(ready)try{saveMark();}catch(Exception ignored){}release();want=false;abandonFocus();
        if(!t.remote())waitForMountUntil=SystemClock.elapsedRealtime()+90000;
        Journal.event(this,"播放最终失败："+reason);message=reason;stopForeground(true);state();}
    void release(){if(prepareTimeout!=null){clock.removeCallbacks(prepareTimeout);prepareTimeout=null;}appliedGainDb=0;releaseGain();generation++;waitForMountUntil=0;loading=false;copied=false;ready=false;if(audio!=null){audio.release();audio=null;}if(cached!=null){cached.delete();cached=null;}}
    void releaseGain(){}
    void refreshGain(){
        if(audio==null||!ready)return;
        Track track=current();final SoftPlayer player=audio;
        if(!prefs().getBoolean("replayGain",false)||track==null){applyGain(player,null);return;}
        JSONObject remote=track.data.optJSONObject("replayGain");
        if(track.remote()){applyGain(player,remote);return;}
        String key=gainKey(track);
        if(gainCache.containsKey(key)){applyGain(player,gainCache.get(key));return;}
        applyGain(player,null);
        reader.execute(()->{JSONObject tags=new JSONObject();try{tags=ReplayGain.read(this,Uri.parse(track.id()));}catch(Exception ex){Journal.event(this,"ReplayGain 标签读取失败 · "+ex.getClass().getSimpleName());}
            final JSONObject value=tags;clock.post(()->{if(audio!=player)return;if(gainCache.size()>256)gainCache.clear();gainCache.put(key,value);if(prefs().getBoolean("replayGain",false))applyGain(player,value);});});
    }
    void applyGain(SoftPlayer player,JSONObject tags){
        if(player!=audio||!ready)return;releaseGain();double gain=ReplayGain.db(tags);appliedGainDb=gain;
        try{player.setGain(gain);Journal.event(this,"ReplayGain PCM 增益 "+String.format(java.util.Locale.ROOT,"%.2f dB",gain));}
        catch(Exception ex){appliedGainDb=0;player.setGain(0);Journal.event(this,"ReplayGain 不可用 · "+ex.getClass().getSimpleName());}
    }
    PendingIntent command(String a,int code){return PendingIntent.getService(this,code,new Intent(this,Deck.class).setAction(a),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);}
    void state(){
        Track t=current();
        long actions=PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE|PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS|PlaybackState.ACTION_SEEK_TO;
        int s=t==null?PlaybackState.STATE_STOPPED:playing()?PlaybackState.STATE_PLAYING:want&&!focusPaused?PlaybackState.STATE_BUFFERING:PlaybackState.STATE_PAUSED;
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions).setState(s,position(),playing()?1f:0f).build());
        if(t!=null){session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,t.title()).putString(MediaMetadata.METADATA_KEY_ARTIST,t.get("artist")).putLong(MediaMetadata.METADATA_KEY_DURATION,duration()).build());
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            Notification.Builder b;
            if(Build.VERSION.SDK_INT>=26){nm.createNotificationChannel(new NotificationChannel("music","音乐播放",NotificationManager.IMPORTANCE_LOW));b=new Notification.Builder(this,"music");}else b=new Notification.Builder(this);
            Notification n=b.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(t.title()).setContentText(t.get("artist"))
              .setContentIntent(PendingIntent.getActivity(this,0,new Intent(this,Home.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT))
              .addAction(want?android.R.drawable.ic_media_pause:android.R.drawable.ic_media_play,want?"暂停":"播放",command("toggle",1))
              .addAction(android.R.drawable.ic_media_next,"下一首",command("next",2))
              .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0,1)).build();
            if(want)startForeground(7,n);else if(ready)nm.notify(7,n);
        }else{session.setMetadata(null);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(7);}
        if(change!=null)change.run();
    }
    public void onDestroy(){destroyed=true;unregisterReceiver(storage);unregisterReceiver(noisy);clock.removeCallbacksAndMessages(null);saveMark();release();abandonFocus();reader.shutdownNow();session.release();stopForeground(true);super.onDestroy();}
}

