package com.vicationfly.app;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    final int ORANGE = Color.rgb(255,122,0), DARK = Color.rgb(38,30,25), CREAM=Color.rgb(255,248,240);
    LinearLayout root, content;
    SharedPreferences prefs;
    ArrayList<String> vacations = new ArrayList<>();

    int dp(float v){ return (int)(v*getResources().getDisplayMetrics().density+0.5f); }
    TextView tv(String s,float size,int color){ TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); return t; }
    GradientDrawable bg(int color,float r){ GradientDrawable g=new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(r)); return g; }
    Button button(String text){ Button b=new Button(this); b.setText(text); b.setTextSize(16); b.setTextColor(Color.WHITE); b.setAllCaps(false); b.setBackground(bg(ORANGE,18)); b.setPadding(dp(18),dp(10),dp(18),dp(10)); return b; }

    @Override public void onCreate(Bundle b){
        super.onCreate(b); getWindow().setStatusBarColor(CREAM); getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        prefs=getSharedPreferences("vicationfly",0);
        loadVacations();
        showSplash();
    }

    void showSplash(){
        setContentView(new SplashView(this));
        new Handler().postDelayed(this::showHome, 3500);
    }

    void base(){
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(CREAM);
        setContentView(root);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(24),dp(24),dp(24),dp(32));
        scroll.addView(content); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    }

    void showHome(){
        base();
        TextView brand=tv("Vicationfly",32,DARK); brand.setTypeface(null,1);
        content.addView(brand,new LinearLayout.LayoutParams(-1,dp(54)));
        TextView sub=tv("a whole vocation in one place",15,Color.DKGRAY);
        content.addView(sub,new LinearLayout.LayoutParams(-1,dp(34)));

        Button create=button("+  Create Vication");
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(62)); cp.setMargins(0,dp(8),0,dp(20));
        content.addView(create,cp); create.setOnClickListener(v->showName());

        if(vacations.isEmpty()){
            TextView empty=tv("No vacations yet",23,DARK); empty.setGravity(Gravity.CENTER);
            content.addView(empty,new LinearLayout.LayoutParams(-1,dp(100)));
            TextView hint=tv("Create your first vocation to start planning flights, hotels and more.",15,Color.GRAY); hint.setGravity(Gravity.CENTER); hint.setPadding(dp(20),0,dp(20),0);
            content.addView(hint,new LinearLayout.LayoutParams(-1,dp(90)));
        } else {
            TextView title=tv("Your vacations",22,DARK); title.setTypeface(null,1);
            content.addView(title,new LinearLayout.LayoutParams(-1,dp(45)));
            for(String name: vacations) addCard(name);
        }
    }

    void addCard(String name){
        LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(20),dp(16),dp(20),dp(16)); card.setBackground(bg(Color.WHITE,22));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(105)); p.setMargins(0,0,0,dp(14));
        TextView n=tv(name,20,DARK); n.setTypeface(null,1);
        TextView d=tv("Your vocation • flights & plans",13,Color.GRAY);
        card.addView(n,new LinearLayout.LayoutParams(-1,dp(42))); card.addView(d);
        content.addView(card,p);
    }

    void showName(){
        base();
        TextView back=tv("‹  Back",18,ORANGE); content.addView(back,new LinearLayout.LayoutParams(-1,dp(50))); back.setOnClickListener(v->showHome());
        TextView title=tv("Name your vication",31,DARK); title.setTypeface(null,1); content.addView(title,new LinearLayout.LayoutParams(-1,dp(65)));
        TextView desc=tv("Give your trip a name so you can find it later.",15,Color.GRAY); content.addView(desc,new LinearLayout.LayoutParams(-1,dp(50)));
        EditText input=new EditText(this); input.setTextSize(18); input.setHint("e.g. Summer in Italy"); input.setSingleLine(true); input.setPadding(dp(18),0,dp(18),0); input.setBackground(bg(Color.WHITE,18));
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,dp(62)); ip.setMargins(0,dp(20),0,dp(20)); content.addView(input,ip);
        Button next=button("Continue  →"); content.addView(next,new LinearLayout.LayoutParams(-1,dp(60)));
        next.setOnClickListener(v->{String s=input.getText().toString().trim(); if(s.isEmpty()){input.setError("Please enter a name");return;} vacations.add(s); saveVacations(); ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0); showHome();});
        input.requestFocus();
    }

    void loadVacations(){String all=prefs.getString("vacations",""); if(!all.isEmpty()) vacations.addAll(Arrays.asList(all.split("\\u001F",-1)));}
    void saveVacations(){StringBuilder s=new StringBuilder(); for(String v:vacations){if(s.length()>0)s.append('\u001F');s.append(v);} prefs.edit().putString("vacations",s.toString()).apply();}

    class SplashView extends View {
        Paint p=new Paint(3); long start=System.currentTimeMillis();
        SplashView(Context c){super(c); p.setTypeface(Typeface.create("sans",Typeface.NORMAL));}
        protected void onDraw(Canvas c){
            super.onDraw(c); c.drawColor(CREAM);
            float w=getWidth(),h=getHeight(); p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.create("sans",Typeface.BOLD)); p.setTextSize(dp(42)); p.setColor(DARK); c.drawText("Vicationfly",w/2,h*.39f,p);
            p.setTypeface(Typeface.create("sans",Typeface.NORMAL)); p.setTextSize(dp(16)); p.setColor(Color.rgb(100,90,82)); c.drawText("a whole vocation in one place",w/2,h*.44f,p);
            float t=((System.currentTimeMillis()-start)%1800)/1800f; float x=-dp(60)+t*(w+dp(120)); drawPlane(c,x,h*.62f);
            p.setColor(Color.rgb(255,190,130)); p.setStrokeWidth(dp(3)); c.drawLine(dp(45),h*.66f,w-dp(45),h*.66f,p);
            p.setColor(ORANGE); c.drawCircle(dp(45)+t*(w-dp(90)),h*.66f,dp(5),p);
            p.setTextSize(dp(13)); p.setColor(Color.GRAY); c.drawText("Loading flights around the world…",w/2,h*.73f,p);
            postInvalidateDelayed(16);
        }
        void drawPlane(Canvas c,float x,float y){
            p.setColor(ORANGE); p.setStyle(Paint.Style.FILL);
            Path a=new Path(); a.moveTo(x,y); a.lineTo(x+dp(54),y-dp(9)); a.lineTo(x+dp(73),y); a.lineTo(x+dp(54),y+dp(9)); a.close(); c.drawPath(a,p);
            Path wing=new Path(); wing.moveTo(x+dp(29),y); wing.lineTo(x+dp(7),y-dp(25)); wing.lineTo(x+dp(18),y-dp(19)); wing.lineTo(x+dp(40),y); wing.close(); c.drawPath(wing,p);
            Path wing2=new Path(); wing2.moveTo(x+dp(32),y); wing2.lineTo(x+dp(10),y+dp(24)); wing2.lineTo(x+dp(21),y+dp(18)); wing2.lineTo(x+dp(43),y); wing2.close(); c.drawPath(wing2,p);
        }
    }
}
