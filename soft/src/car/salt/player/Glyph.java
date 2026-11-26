package car.salt.soft;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Small vector controls keep the player usable without font-specific symbol glyphs. */
final class Glyph extends Drawable {
    final String kind;
    final int color;
    Glyph(String kind,int color){this.kind=kind;this.color=color;}
    public void draw(Canvas canvas){
        canvas.save();
        canvas.translate(getBounds().left,getBounds().top);
        canvas.scale(getBounds().width()/24f,getBounds().height()/24f);
        Paint paint=new Paint(3);paint.setColor(color);paint.setStrokeWidth(1.7f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        Path path=new Path();
        switch(kind){
            case "theme": paint.setStyle(Paint.Style.STROKE);canvas.drawCircle(12,12,8,paint);paint.setStyle(Paint.Style.FILL);canvas.drawArc(4,4,20,20,90,180,true,paint);break;
            case "record": paint.setColor(0xff242623);canvas.drawCircle(12,12,5.7f,paint);paint.setColor(0xff060b10);canvas.drawCircle(12,12,2.7f,paint);paint.setColor(0xff222621);canvas.drawCircle(12,12,1.1f,paint);break;
            case "sort": canvas.drawLine(6,3,6,21,paint);canvas.drawLine(3,18,6,21,paint);canvas.drawLine(9,18,6,21,paint);canvas.drawLine(13,5,21,5,paint);canvas.drawLine(13,11,19,11,paint);canvas.drawLine(13,17,16,17,paint);break;
            case "play": path.moveTo(7,4);path.lineTo(20,12);path.lineTo(7,20);path.close();canvas.drawPath(path,paint);break;
            case "pause": canvas.drawRoundRect(6,4,10,20,1,1,paint);canvas.drawRoundRect(14,4,18,20,1,1,paint);break;
            case "next": path.moveTo(5,5);path.lineTo(16,12);path.lineTo(5,19);path.close();canvas.drawPath(path,paint);canvas.drawRect(17,5,19,19,paint);break;
            case "previous": path.moveTo(19,5);path.lineTo(8,12);path.lineTo(19,19);path.close();canvas.drawPath(path,paint);canvas.drawRect(5,5,7,19,paint);break;
            case "search": paint.setStyle(Paint.Style.STROKE);canvas.drawCircle(10,10,6,paint);canvas.drawLine(15,15,21,21,paint);break;
            case "queue": for(int i=0;i<3;i++){canvas.drawCircle(4,6+i*6,1,paint);canvas.drawLine(9,6+i*6,21,6+i*6,paint);}break;
            case "more": for(int i=0;i<3;i++)canvas.drawCircle(5+i*7,12,1.6f,paint);break;
            case "back": canvas.drawLine(5,9,12,16,paint);canvas.drawLine(12,16,19,9,paint);break;
            case "folder": path.moveTo(2,6);path.lineTo(10,6);path.lineTo(12,8);path.lineTo(22,8);path.lineTo(22,20);path.lineTo(2,20);path.close();canvas.drawPath(path,paint);break;
            case "disc": canvas.drawCircle(12,12,9,paint);paint.setColor(color==0xfffdfaff?0xffe8e3ef:0xfff8f5fc);canvas.drawCircle(12,12,3,paint);break;
            case "info": paint.setStyle(Paint.Style.STROKE);canvas.drawCircle(12,12,9,paint);canvas.drawLine(12,11,12,17,paint);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(12,7,1,paint);break;
            case "repeatOne": paint.setTextSize(9);paint.setTextAlign(Paint.Align.CENTER);canvas.drawText("1",12,15,paint);
            case "repeat": paint.setStyle(Paint.Style.STROKE);canvas.drawLine(4,5,20,5,paint);canvas.drawLine(20,19,4,19,paint);canvas.drawLine(16,1,20,5,paint);canvas.drawLine(8,23,4,19,paint);break;
            case "shuffle": canvas.drawLine(3,6,20,18,paint);canvas.drawLine(3,18,20,6,paint);canvas.drawLine(16,5,21,5,paint);canvas.drawLine(21,5,21,10,paint);break;
            default: paint.setStyle(Paint.Style.STROKE);canvas.drawCircle(12,12,7,paint);canvas.drawLine(12,2,12,5,paint);canvas.drawLine(12,19,12,22,paint);canvas.drawLine(2,12,5,12,paint);canvas.drawLine(19,12,22,12,paint);
        }
        canvas.restore();
    }
    public void setAlpha(int alpha){}
    public void setColorFilter(ColorFilter filter){}
    public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
