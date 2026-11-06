package io.github.syldegil.autotunnel;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
/** Shared inspector palette and rounded native controls. */
final class AppStyle {
    static boolean dark(Context c){return (c.getResources().getConfiguration().uiMode&48)==32;}
    static int color(Context c,String light,String dark){return Color.parseColor(dark(c)?dark:light);}
    static int bg(Context c){return color(c,"#f2f5f8","#0c121b");}
    static int panel(Context c){return color(c,"#ffffff","#141e2b");}
    static int ink(Context c){return color(c,"#15283a","#eff5fc");}
    static int muted(Context c){return color(c,"#53677b","#9caec2");}
    static int green(Context c){return color(c,"#086a50","#77e0bd");}
    static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static void theme(Activity a){a.setTheme(dark(a)?R.style.GraphTheme:R.style.AppTheme);}
    static void card(View v){GradientDrawable d=new GradientDrawable();d.setColor(panel(v.getContext()));d.setCornerRadius(dp(v.getContext(),20));v.setBackground(d);}
    static void button(Button b){Context c=b.getContext();b.setAllCaps(false);b.setTextColor(ink(c));b.setMinHeight(dp(c,48));b.setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8));GradientDrawable shape=new GradientDrawable();shape.setColor(panel(c));shape.setCornerRadius(dp(c,16));b.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(color(c,"#dce9f8","#27465d")),shape,null));}
    static void tree(View v){Context c=v.getContext();if(v instanceof TextView)((TextView)v).setTextColor(ink(c));if(v instanceof Button)button((Button)v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)tree(((ViewGroup)v).getChildAt(i));}
}
