package io.github.syldegil.autotunnel;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;

/** Small native visual components; text carries all states, colour is supplementary. */
final class InspectorVisuals {
    static int dp(Context c,float v){return Math.round(v*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable shape(int color,int radius,Context c){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d;}
    static TextView text(LinearLayout parent,String value,int size,int color){TextView t=new TextView(parent.getContext());t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(parent.getContext(),3),0,dp(parent.getContext(),3));parent.addView(t);return t;}
    static final class Icon extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG); private String kind; private int color;
        Icon(Context c,String kind,int color){super(c);this.kind=kind;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void kind(String value){kind=value;invalidate();}
        void color(int color){this.color=color;invalidate();}
        protected void onDraw(Canvas canvas){super.onDraw(canvas);canvas.save();float scale=Math.min(getWidth(),getHeight())/48f;canvas.translate((getWidth()-48*scale)/2,(getHeight()-48*scale)/2);canvas.scale(scale,scale);
            paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.2f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
            if(kind.equals("wifi")){canvas.drawArc(3,7,45,47,220,100,false,paint);canvas.drawArc(10,16,38,42,220,100,false,paint);canvas.drawArc(17,25,31,39,220,100,false,paint);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(24,35,2.5f,paint);}
            else if(kind.equals("mobile")){canvas.drawCircle(24,13,3,paint);canvas.drawLine(24,16,24,40,paint);canvas.drawLine(24,25,15,40,paint);canvas.drawLine(24,25,33,40,paint);canvas.drawArc(8,0,40,30,140,80,false,paint);canvas.drawArc(8,0,40,30,-40,80,false,paint);}
            else if(kind.equals("pulse")){Path p=new Path();p.moveTo(4,26);p.lineTo(14,26);p.lineTo(20,12);p.lineTo(28,38);p.lineTo(34,22);p.lineTo(44,22);canvas.drawPath(p,paint);}
            else if(kind.equals("graph")){canvas.drawLine(14,14,34,24,paint);canvas.drawLine(14,36,34,24,paint);canvas.drawRoundRect(5,6,19,20,3,3,paint);canvas.drawRoundRect(5,29,19,43,3,3,paint);canvas.drawRoundRect(29,17,43,31,3,3,paint);}
            else if(kind.equals("usb")){canvas.drawLine(24,7,24,39,paint);canvas.drawLine(24,7,19,13,paint);canvas.drawLine(24,7,29,13,paint);canvas.drawLine(24,26,13,20,paint);canvas.drawLine(13,20,13,14,paint);canvas.drawCircle(13,11,3,paint);canvas.drawLine(24,32,35,26,paint);canvas.drawLine(35,26,35,20,paint);canvas.drawRect(32,14,38,20,paint);canvas.drawCircle(24,40,3,paint);}
            else if(kind.equals("phone")){canvas.drawRoundRect(13,5,35,43,5,5,paint);canvas.drawLine(21,37,27,37,paint);}
            else if(kind.equals("shield")){Path p=new Path();p.moveTo(24,5);p.lineTo(39,11);p.lineTo(37,27);p.quadTo(33,37,24,43);p.quadTo(15,37,11,27);p.lineTo(9,11);p.close();canvas.drawPath(p,paint);}
            else if(kind.equals("globe")){canvas.drawCircle(24,24,18,paint);canvas.drawOval(16,6,32,42,paint);canvas.drawLine(6,24,42,24,paint);canvas.drawArc(8,11,40,23,0,180,false,paint);canvas.drawArc(8,25,40,37,180,180,false,paint);}
            else if(kind.equals("check")){canvas.drawCircle(24,24,18,paint);canvas.drawLine(15,24,22,31,paint);canvas.drawLine(22,31,34,17,paint);}
            else if(kind.equals("server")){canvas.drawRoundRect(7,9,41,39,5,5,paint);canvas.drawLine(7,24,41,24,paint);canvas.drawCircle(14,17,1,paint);canvas.drawCircle(14,31,1,paint);canvas.drawLine(23,17,34,17,paint);canvas.drawLine(23,31,34,31,paint);}
            else {canvas.drawCircle(24,24,18,paint);canvas.drawLine(24,13,24,25,paint);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(24,33,1.5f,paint);}
            canvas.restore();}
    }
    static final class StepRow extends LinearLayout {
        final TextView state; final TextView duration; final Icon icon; private final int good,muted,bad;
        StepRow(Context c,String title,String kind,int ink,int muted,int good,int bad){super(c);this.good=good;this.muted=muted;this.bad=bad;setGravity(android.view.Gravity.CENTER_VERTICAL);setPadding(0,dp(c,10),0,dp(c,10));
            icon=new Icon(c,kind,muted);addView(icon,new LayoutParams(dp(c,40),dp(c,40)));LinearLayout copy=new LinearLayout(c);copy.setOrientation(VERTICAL);LayoutParams p=new LayoutParams(0,-2,1);p.setMargins(dp(c,12),0,0,0);addView(copy,p);text(copy,title,13,muted);state=text(copy,"Pas encore testé",17,ink);duration=new TextView(c);duration.setTextSize(12);duration.setTextColor(muted);addView(duration);}
        void update(int index,DiagnosticSequence.Step step){DiagnosticSequence.Status s=step==null?DiagnosticSequence.Status.NOT_RUN:step.status;int color=s==DiagnosticSequence.Status.PASS?good:s==DiagnosticSequence.Status.FAIL||s==DiagnosticSequence.Status.TIMEOUT?bad:muted;state.setText(InspectorMeaning.stage(index,s));icon.color(color);duration.setText(step==null||s==DiagnosticSequence.Status.NOT_RUN?"":step.millis+" ms");setContentDescription((index+1)+". "+state.getText()+" "+duration.getText());}
    }
}
