package car.salt.player;

import android.app.AlertDialog;
import android.net.Uri;
import android.os.Environment;
import android.view.KeyEvent;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

/** Small fallback for firmware without DocumentsUI. Lists only the current directory. */
final class FolderPicker {
    private final Home home;
    private final ListView list;
    private final AlertDialog dialog;
    private File current;
    private int generation;
    private boolean closed;
    FolderPicker(Home home){
        this.home=home;
        list=new ListView(home);
        dialog=new AlertDialog.Builder(home).setTitle("选择音乐文件夹")
            .setView(list).setNegativeButton("取消",null)
            .setPositiveButton("选择此文件夹",null).create();
        dialog.setOnDismissListener(d->{closed=true;generation++;});
        dialog.setOnKeyListener((d,key,event)->{
            if(key==KeyEvent.KEYCODE_BACK&&current!=null){
                if(event.getAction()==KeyEvent.ACTION_UP)parent();
                return true;
            }
            return false;
        });
        dialog.setOnShowListener(d->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                if(current!=null){File chosen=current;dialog.dismiss();home.scan(Uri.fromFile(chosen));}
            });
            show(null);
        });
    }
    static void open(Home home){new FolderPicker(home).dialog.show();}
    private void parent(){
        File parent=current.getParentFile();
        show(parent==null||current.equals(Environment.getExternalStorageDirectory())||"/storage".equals(parent.getPath())?null:parent);
    }
    private void show(File folder){
        current=folder;final int token=++generation;
        dialog.setTitle(folder==null?"选择存储位置":folder.getAbsolutePath());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        list.setAdapter(new ArrayAdapter<String>(home,android.R.layout.simple_list_item_1,new String[]{"读取中…"}));
        list.setOnItemClickListener(null);
        home.io.execute(()->{
            ArrayList<File> dirs=new ArrayList<>();String error="";
            try{
                if(folder==null){
                    dirs.add(Environment.getExternalStorageDirectory());
                    File[] storage=new File("/storage").listFiles();
                    if(storage!=null)for(File f:storage)if(f.isDirectory()&&f.canRead()&&!f.getName().equals("emulated")&&!f.getName().equals("self")){
                        boolean duplicate=false;for(File old:dirs)if(old.getCanonicalFile().equals(f.getCanonicalFile()))duplicate=true;
                        if(!duplicate)dirs.add(f);
                    }
                }else{
                    File[] files=folder.listFiles();if(files==null)throw new IOException();
                    Arrays.sort(files,(a,b)->a.getName().compareToIgnoreCase(b.getName()));
                    for(File f:files)if(f.isDirectory()&&!f.isHidden())dirs.add(f);
                }
            }catch(Exception e){error="目录不可读，请返回选择其他文件夹";}
            final String problem=error;
            home.ui(()->{
                if(closed||token!=generation)return;
                ArrayList<String> names=new ArrayList<>();
                if(folder!=null)names.add("↑ 上一级");
                for(File f:dirs)names.add(folder==null?f.getAbsolutePath():f.getName());
                if(!problem.isEmpty())names.add(problem);
                else if(folder!=null&&dirs.isEmpty())names.add("没有子文件夹，可选择当前文件夹");
                list.setAdapter(new ArrayAdapter<String>(home,android.R.layout.simple_list_item_1,names));
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(folder!=null&&problem.isEmpty());
                list.setOnItemClickListener((p,v,index,id)->{
                    if(folder!=null&&index==0){parent();return;}
                    int n=index-(folder==null?0:1);if(n>=0&&n<dirs.size())show(dirs.get(n));
                });
                list.requestFocus();list.setSelection(0);
            });
        });
    }
}
