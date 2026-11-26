package car.salt.player;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Focus identity survives rebuilding a page; touch layouts remain unchanged. */
final class RemoteUi {
    final Home home;
    boolean active;
    int swallowed;
    ListView confirming;
    int confirmPosition;
    boolean longConfirm;
    Runnable hold;
    String screen="";
    View root;
    final IdentityHashMap<View,String> keys=new IdentityHashMap<>();
    final HashSet<View> touchFocus=new HashSet<>();
    final Map<String,String> saved=new HashMap<>(),songs=new HashMap<>();
    RemoteUi(Home h){home=h;}
    String scope(){return home.page+"|"+home.category+"|"+home.group+"|"+home.query+"|"+home.serverSearch;}
    void remember(){
        cancelHold();
        if(root==null||!active)return;View focus=root.findFocus();
        if(focus!=null&&keys.containsKey(focus))saved.put(screen,keys.get(focus));
        if(focus instanceof ListView){ListView list=(ListView)focus;Object item=list.getSelectedItem();
            if(item instanceof Track)songs.put(screen,home.trackKey((Track)item));
        }
        if(saved.size()>64){saved.clear();songs.clear();}
    }
    void cancelHold(){if(hold!=null)home.timer.removeCallbacks(hold);hold=null;confirming=null;}
    void touch(){active=false;cancelHold();for(View v:touchFocus)v.setFocusableInTouchMode(false);touchFocus.clear();}
    void longConfirm(){
        if(confirming==null||longConfirm)return;
        Object item=confirming.getSelectedItem();
        if(item instanceof Track){longConfirm=true;home.trackMenu((Track)item);}
    }
    StateListDrawable highlight(int color){
        return highlight(color,false);
    }
    StateListDrawable highlight(int color,boolean circle){
        GradientDrawable glow=new GradientDrawable();glow.setColor((color&0xffffff)|0x22000000);glow.setCornerRadius(home.dp(8));glow.setStroke(home.dp(2),color);
        Drawable focused=glow;
        if(circle){
            glow.setShape(GradientDrawable.OVAL);
            // Keep a true circle centered inside non-square button touch targets.
            focused=new InsetDrawable(glow,0){
                @Override protected void onBoundsChange(Rect bounds){
                    super.onBoundsChange(bounds);
                    int size=Math.min(bounds.width(),bounds.height());
                    int left=bounds.left+(bounds.width()-size)/2,top=bounds.top+(bounds.height()-size)/2;
                    getDrawable().setBounds(left,top,left+size,top+size);
                }
            };
        }
        StateListDrawable d=new StateListDrawable();d.addState(new int[]{android.R.attr.state_focused},focused);d.addState(new int[]{},new ColorDrawable(Color.TRANSPARENT));return d;
    }
    String label(View v){
        if(v==home.playIcon)return "播放暂停";
        if(v.getContentDescription()!=null)return v.getContentDescription().toString();
        if(v instanceof TextView)return ((TextView)v).getText().toString();
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){String text=label(g.getChildAt(i));if(!text.isEmpty())return text;}}
        return "";
    }
    boolean roundButton(View v){
        if(v instanceof ImageView)return v!=home.miniCover;
        if(v instanceof FrameLayout){ViewGroup g=(ViewGroup)v;return g.getChildCount()==1&&g.getChildAt(0) instanceof ImageView;}
        return false;
    }
    void bind(View v,String path,int color){
        if(v instanceof ListView){ListView list=(ListView)v;keys.put(v,"list:"+path);list.setSelector(highlight(color));list.setDrawSelectorOnTop(true);return;}
        if(v instanceof ScrollView&&v!=home.lyricScroll)v.setFocusable(false);
        if(v.isClickable()||v.isFocusable()||v instanceof SeekBar||v==home.lyricScroll){
            v.setFocusable(true);v.setForeground(highlight(color,roundButton(v)));
            String name=label(v);keys.put(v,name.isEmpty()?path:name);
        }
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)bind(g.getChildAt(i),path+"/"+i,color);}
    }
    void install(int color){
        root=home.root;screen=scope();keys.clear();touchFocus.clear();bind(root,"root",color);
        View target=root;target.post(()->{if(root==target)restore();});
    }
    View find(String key){for(Map.Entry<View,String> e:keys.entrySet())if(e.getValue().equals(key)&&e.getKey().isEnabled()&&e.getKey().isShown())return e.getKey();return null;}
    void restore(){
        View next=find(saved.get(screen));
        if(next==null&&home.page.equals("歌词"))next=home.playIcon;
        if(next==null)for(View v:keys.keySet())if(v instanceof ListView&&((ListView)v).getCount()>0){next=v;break;}
        if(next==null)next=find("设置与日志");
        if(next==null)next=find("返回曲库");
        if(next!=null){next.setFocusableInTouchMode(true);touchFocus.add(next);next.requestFocusFromTouch();if(next instanceof ListView){ListView list=(ListView)next;String id=songs.get(screen);int position=0;
            for(int i=0;i<list.getCount();i++){Object item=list.getItemAtPosition(i);if(item instanceof Track&&home.trackKey((Track)item).equals(id)){position=i;break;}}
            if(list.getCount()>0)list.setSelection(position);
        }}
    }
    boolean key(KeyEvent event){
        int code=event.getKeyCode();boolean direction=code>=KeyEvent.KEYCODE_DPAD_UP&&code<=KeyEvent.KEYCODE_DPAD_RIGHT;
        if(event.getAction()==KeyEvent.ACTION_UP&&swallowed==code){swallowed=0;return true;}
        if(direction||code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER){
            active=true;
            if(event.getAction()==KeyEvent.ACTION_DOWN&&(root.findFocus()==null||!keys.containsKey(root.findFocus()))){restore();swallowed=code;return true;}
        }
        View focus=root==null?null:root.findFocus();
        if((code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER)&&focus instanceof ListView&&((ListView)focus).getSelectedItem() instanceof Track){
            if(event.getAction()==KeyEvent.ACTION_DOWN){
                if(event.getRepeatCount()==0){cancelHold();confirming=(ListView)focus;confirmPosition=confirming.getSelectedItemPosition();longConfirm=false;hold=()->longConfirm();home.timer.postDelayed(hold,ViewConfiguration.getLongPressTimeout());}
                else longConfirm();
            }else if(event.getAction()==KeyEvent.ACTION_UP){
                ListView list=confirming;boolean click=!longConfirm&&!event.isCanceled();int position=confirmPosition;cancelHold();
                if(click&&list!=null&&list.getSelectedItemPosition()==position)list.performItemClick(list.getSelectedView(),position,list.getItemIdAtPosition(position));
            }
            return true;
        }
        if(direction&&event.getAction()==KeyEvent.ACTION_DOWN)cancelHold();
        if(code==KeyEvent.KEYCODE_MENU&&focus instanceof ListView){
            if(event.getAction()==KeyEvent.ACTION_UP){Object item=((ListView)focus).getSelectedItem();if(item instanceof Track)home.trackMenu((Track)item);}
            return true;
        }
        return false;
    }
}
