package com.luma.app;

import android.app.*;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.content.*;
import android.view.*;
import android.view.animation.AlphaAnimation;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    LinearLayout messages;
    EditText input;
    SharedPreferences prefs;
    ArrayList<String> history = new ArrayList<>();

    int dp(float v){ return Math.round(v * getResources().getDisplayMetrics().density); }
    GradientDrawable bg(int color, float radius){ GradientDrawable g=new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius)); return g; }
    TextView txt(String s,int size,int color){ TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); return t; }

    @Override public void onCreate(Bundle b){ super.onCreate(b); prefs=getSharedPreferences("luma",MODE_PRIVATE); build(); load(); }

    void build(){
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18),dp(16),dp(18),dp(14)); root.setBackgroundColor(Color.rgb(246,247,251));
        LinearLayout top=new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo=txt("Luma",30,Color.rgb(27,29,38)); logo.setTypeface(null,1); top.addView(logo,new LinearLayout.LayoutParams(0,dp(56),1));
        Button settings=new Button(this); settings.setText("⚙"); settings.setOnClickListener(v->settings()); top.addView(settings,new LinearLayout.LayoutParams(dp(58),dp(52))); root.addView(top);

        TextView hero=txt("Anything is possible with Luma",20,Color.rgb(82,70,190)); hero.setGravity(Gravity.CENTER); hero.setPadding(0,dp(10),0,dp(14)); root.addView(hero);
        AlphaAnimation pulse=new AlphaAnimation(.45f,1f); pulse.setDuration(1200); pulse.setRepeatMode(2); pulse.setRepeatCount(-1); hero.startAnimation(pulse);

        ScrollView sc=new ScrollView(this); messages=new LinearLayout(this); messages.setOrientation(LinearLayout.VERTICAL); messages.setPadding(0,dp(8),0,dp(8)); sc.addView(messages); root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout tools=new LinearLayout(this); String[] names={"Chat","Image","Edit"}; for(String n:names){ Button x=new Button(this); x.setText(n); tools.addView(x,new LinearLayout.LayoutParams(0,dp(46),1)); } root.addView(tools);
        LinearLayout bar=new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); input=new EditText(this); input.setHint("Message Luma…"); input.setSingleLine(false); input.setMaxLines(4); input.setBackground(bg(Color.WHITE,18)); input.setPadding(dp(14),dp(10),dp(14),dp(10)); bar.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        Button send=new Button(this); send.setText("➤"); send.setOnClickListener(v->send()); bar.addView(send,new LinearLayout.LayoutParams(dp(64),dp(58))); root.addView(bar);
        setContentView(root);
    }

    void bubble(String s, boolean user){
        TextView t=txt(s,16,user?Color.WHITE:Color.rgb(35,37,45)); t.setPadding(dp(14),dp(10),dp(14),dp(10)); t.setBackground(bg(user?Color.rgb(94,76,220):Color.WHITE,16));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2); p.gravity=user?Gravity.END:Gravity.START; p.setMargins(0,dp(5),0,dp(5)); messages.addView(t,p);
    }

    void send(){ String q=input.getText().toString().trim(); if(q.isEmpty()) return; input.setText(""); add("U|"+q); bubble(q,true); String r="Luma is ready. Connect an AI backend/API to replace this local response with real model output."; add("A|"+r); bubble(r,false); }
    void add(String s){ history.add(s); prefs.edit().putString("history",String.join("\n",history)).apply(); }
    void load(){ String h=prefs.getString("history",""); if(h.isEmpty()){ bubble("Hi — I’m Luma. Your chat history is stored on this device.",false); return; } for(String x:h.split("\n")){ if(x.length()>2) bubble(x.substring(2),x.startsWith("U|")); history.add(x); } }
    void settings(){ new AlertDialog.Builder(this).setTitle("Luma Settings").setItems(new String[]{"Clear chat history","Response style: Balanced","Animations: On","About Luma 1.0"},(d,w)->{ if(w==0){ prefs.edit().clear().apply(); history.clear(); messages.removeAllViews(); bubble("Chat history cleared.",false); }}).setNegativeButton("Close",null).show(); }
}
