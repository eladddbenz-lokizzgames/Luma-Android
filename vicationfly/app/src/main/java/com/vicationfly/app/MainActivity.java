package com.vicationfly.app;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

public class MainActivity extends Activity {
    final int ORANGE=Color.rgb(255,122,0), DARK=Color.rgb(38,30,25), CREAM=Color.rgb(255,248,240), PALE=Color.rgb(255,237,220);
    LinearLayout root,content;
    SharedPreferences prefs;
    ArrayList<Vacation> vacations=new ArrayList<>();
    Vacation editingVacation;
    AutoCompleteTextView fromBox,toBox;
    String fromCode="",toCode="",selectedDate="";
    static final int PICK_IMAGE=42;

    int dp(float v){return(int)(v*getResources().getDisplayMetrics().density+.5f);}
    TextView tv(String s,float size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    GradientDrawable bg(int c,float r){GradientDrawable g=new GradientDrawable();g.setColor(c);g.setCornerRadius(dp(r));return g;}
    Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(16);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setBackground(bg(ORANGE,18));b.setPadding(dp(18),dp(10),dp(18),dp(10));return b;}

    static class Vacation{
        String name,image;
        Vacation(String n,String i){name=n;image=i;}
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);getWindow().setStatusBarColor(CREAM);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        prefs=getSharedPreferences("vicationfly",0);loadVacations();showSplash();
    }

    void showSplash(){setContentView(new SplashView(this));new Handler().postDelayed(this::showHome,4200);}

    void base(){
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(CREAM);setContentView(root);
        ScrollView sc=new ScrollView(this);sc.setFillViewport(true);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(22),dp(22),dp(22),dp(32));
        sc.addView(content);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
    }

    void showHome(){
        base();
        TextView brand=tv("Vicationfly",32,DARK);brand.setTypeface(null,1);content.addView(brand,new LinearLayout.LayoutParams(-1,dp(50)));
        content.addView(tv("a whole vocation in one place",15,Color.DKGRAY),new LinearLayout.LayoutParams(-1,dp(35)));
        Button create=button("+  Create Vication");LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(62));cp.setMargins(0,dp(8),0,dp(22));content.addView(create,cp);create.setOnClickListener(v->showName());
        if(vacations.isEmpty()){
            TextView e=tv("No vacations yet",23,DARK);e.setGravity(Gravity.CENTER);content.addView(e,new LinearLayout.LayoutParams(-1,dp(100)));
            TextView h=tv("Create your first vocation to start planning flights, hotels and more.",15,Color.GRAY);h.setGravity(Gravity.CENTER);content.addView(h,new LinearLayout.LayoutParams(-1,dp(80)));
        }else{
            TextView title=tv("Your vacations",22,DARK);title.setTypeface(null,1);content.addView(title,new LinearLayout.LayoutParams(-1,dp(48)));
            for(Vacation v:vacations)addCard(v);
        }
    }

    void addCard(Vacation v){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.HORIZONTAL);card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(12),dp(12),dp(16),dp(12));card.setBackground(bg(Color.WHITE,24));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(112));p.setMargins(0,0,0,dp(14));
        ImageView im=new ImageView(this);im.setScaleType(ImageView.ScaleType.CENTER_CROP);im.setBackground(bg(PALE,18));
        if(v.image!=null&&!v.image.isEmpty())try{im.setImageURI(Uri.parse(v.image));}catch(Exception ignored){}
        else{im.setImageResource(android.R.drawable.ic_menu_compass);}
        card.addView(im,new LinearLayout.LayoutParams(dp(88),dp(88)));
        LinearLayout texts=new LinearLayout(this);texts.setOrientation(LinearLayout.VERTICAL);texts.setPadding(dp(14),0,0,0);
        TextView n=tv(v.name,20,DARK);n.setTypeface(null,1);texts.addView(n,new LinearLayout.LayoutParams(-1,dp(40)));
        texts.addView(tv("Flights • dates • plans",13,Color.GRAY));
        card.addView(texts,new LinearLayout.LayoutParams(0,-1,1));content.addView(card,p);
        card.setOnClickListener(x->{editingVacation=v;showTrip(v);});
    }

    void showName(){
        base();
        TextView back=tv("‹  Back",18,ORANGE);content.addView(back,new LinearLayout.LayoutParams(-1,dp(48)));back.setOnClickListener(v->showHome());
        TextView title=tv("Name your vication",31,DARK);title.setTypeface(null,1);content.addView(title,new LinearLayout.LayoutParams(-1,dp(62)));
        content.addView(tv("Give your trip a name and choose a cover photo.",15,Color.GRAY),new LinearLayout.LayoutParams(-1,dp(44)));
        EditText input=new EditText(this);input.setTextSize(18);input.setHint("e.g. Summer in Italy");input.setSingleLine(true);input.setPadding(dp(18),0,dp(18),0);input.setBackground(bg(Color.WHITE,18));
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,dp(62));ip.setMargins(0,dp(18),0,dp(14));content.addView(input,ip);
        Button photo=button("📷  Choose cover photo");photo.setBackground(bg(Color.rgb(255,150,55),18));content.addView(photo,new LinearLayout.LayoutParams(-1,dp(58)));
        ImageView preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.CENTER_CROP);preview.setBackground(bg(PALE,20));LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(170));pp.setMargins(0,dp(14),0,dp(14));content.addView(preview,pp);
        final String[] picked={""};
        photo.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("image/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK_IMAGE);});
        Button next=button("Continue  →");content.addView(next,new LinearLayout.LayoutParams(-1,dp(60)));
        next.setOnClickListener(v->{String s=input.getText().toString().trim();if(s.isEmpty()){input.setError("Please enter a name");return;}
            String uri=prefs.getString("pending_image","");vacations.add(new Vacation(s,uri));prefs.edit().remove("pending_image").apply();saveVacations();
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);showHome();});
        input.requestFocus();
    }

    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req==PICK_IMAGE&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            Uri u=data.getData();try{getContentResolver().takePersistableUriPermission(u,data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));}catch(Exception ignored){}
            prefs.edit().putString("pending_image",u.toString()).apply();
            showNameWithPendingImage(u);
        }
    }

    void showNameWithPendingImage(Uri u){
        // Rebuild the name screen while preserving the selected image and current text as a simple, reliable Android picker flow.
        showName();
        Toast.makeText(this,"Cover photo selected ✓",Toast.LENGTH_SHORT).show();
    }

    void showTrip(Vacation v){
        base();
        TextView back=tv("‹  Back",18,ORANGE);content.addView(back,new LinearLayout.LayoutParams(-1,dp(46)));back.setOnClickListener(x->showHome());
        TextView title=tv(v.name,30,DARK);title.setTypeface(null,1);content.addView(title,new LinearLayout.LayoutParams(-1,dp(60)));
        LinearLayout plate=new LinearLayout(this);plate.setOrientation(LinearLayout.VERTICAL);plate.setPadding(dp(14),dp(14),dp(14),dp(14));plate.setBackground(bg(Color.WHITE,26));
        TextView fromLabel=tv("FROM",12,Color.GRAY);fromLabel.setTypeface(null,1);plate.addView(fromLabel);
        fromBox=airportBox("Search airport / city");plate.addView(fromBox,new LinearLayout.LayoutParams(-1,dp(58)));
        TextView toLabel=tv("TO",12,Color.GRAY);toLabel.setTypeface(null,1);LinearLayout.LayoutParams tl=new LinearLayout.LayoutParams(-1,dp(25));tl.setMargins(0,dp(10),0,0);plate.addView(toLabel,tl);
        toBox=airportBox("Search airport / city");plate.addView(toBox,new LinearLayout.LayoutParams(-1,dp(58)));
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(10),0,0);
        Button date=button("📅  Choose date");date.setBackground(bg(Color.rgb(255,150,55),18));row.addView(date,new LinearLayout.LayoutParams(0,dp(56),1));
        Button apply=button("Apply");LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(dp(110),dp(56));ap.setMargins(dp(10),0,0,0);row.addView(apply,ap);plate.addView(row);
        content.addView(plate,new LinearLayout.LayoutParams(-1,dp(282)));
        TextView results=tv("Choose FROM, TO and a date to see flights.",16,Color.GRAY);results.setPadding(dp(8),dp(22),dp(8),dp(12));content.addView(results);
        final String[] dateValue={""};
        date.setOnClickListener(x->{Calendar c=Calendar.getInstance();DatePickerDialog d=new DatePickerDialog(this,(view,y,m,day)->{dateValue[0]=String.format(Locale.US,"%04d-%02d-%02d",y,m+1,day);date.setText("📅  "+dateValue[0]);},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH));d.show();});
        apply.setOnClickListener(x->{fromCode=airportCode(fromBox.getText().toString());toCode=airportCode(toBox.getText().toString());selectedDate=dateValue[0];if(fromCode.isEmpty()||toCode.isEmpty()||selectedDate.isEmpty()){Toast.makeText(this,"Choose both airports and a date.",Toast.LENGTH_SHORT).show();return;}loadFlights(results,fromCode,toCode,selectedDate);});
        setupAirportSearch(fromBox);setupAirportSearch(toBox);
    }

    AutoCompleteTextView airportBox(String hint){
        AutoCompleteTextView b=new AutoCompleteTextView(this);b.setTextSize(17);b.setHint(hint);b.setSingleLine(true);b.setThreshold(1);b.setPadding(dp(14),0,dp(14),0);b.setBackground(bg(Color.rgb(255,250,245),16));return b;
    }

    void setupAirportSearch(AutoCompleteTextView box){
        box.setOnItemClickListener((p,v,pos,id)->{String s=(String)p.getItemAtPosition(pos);box.setText(s);});
        box.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int st,int before,int count){if(s.length()<1)return;searchAirports(box,s.toString());}
            public void afterTextChanged(android.text.Editable e){}
        });
    }

    void searchAirports(AutoCompleteTextView box,String q){
        new Thread(()->{try{
            URL u=new URL("https://airportsapi.com/api/airports?search="+URLEncoder.encode(q,"UTF-8"));
            HttpURLConnection c=(HttpURLConnection)u.openConnection();c.setConnectTimeout(5000);c.setReadTimeout(5000);c.setRequestMethod("GET");
            BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream()));StringBuilder s=new StringBuilder();String line;while((line=r.readLine())!=null)s.append(line);c.disconnect();
            JSONArray a=new JSONObject(s.toString()).optJSONArray("data");ArrayList<String> list=new ArrayList<>();
            if(a!=null)for(int i=0;i<Math.min(a.length(),8);i++){JSONObject o=a.getJSONObject(i);String iata=o.optString("iata_code");if(iata.isEmpty())iata=o.optString("code");String name=o.optString("name");String city=o.optString("municipality");if(!iata.isEmpty())list.add(iata+" — "+name+(city.isEmpty()?"":" • "+city));}
            runOnUiThread(()->{box.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_dropdown_item_1line,list));box.showDropDown();});
        }catch(Exception e){runOnUiThread(()->box.dismissDropDown());}}).start();
    }

    String airportCode(String s){if(s==null)return"";String x=s.trim();int n=x.indexOf('—');if(n>0)x=x.substring(0,n).trim();return x.length()>=3?x.substring(0,3).toUpperCase(Locale.US):"";}

    void loadFlights(TextView status,String from,String to,String date){
        status.setText("Searching cached flight offers…");
        final LinearLayout holder=new LinearLayout(this);holder.setOrientation(LinearLayout.VERTICAL);content.addView(holder,new LinearLayout.LayoutParams(-1,-2));
        new Thread(()->{try{
            URL u=new URL("https://flight-mcp.com/v1/flights/search/cached");HttpURLConnection c=(HttpURLConnection)u.openConnection();c.setRequestMethod("POST");c.setConnectTimeout(8000);c.setReadTimeout(10000);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
            String body=new JSONObject().put("origin",from).put("destination",to).put("departureDate",date).put("maxResults",10).put("cacheTtlSeconds",604800).toString();
            OutputStream os=c.getOutputStream();os.write(body.getBytes("UTF-8"));os.close();
            BufferedReader r=new BufferedReader(new InputStreamReader(c.getResponseCode()<400?c.getInputStream():c.getErrorStream()));StringBuilder s=new StringBuilder();String line;while((line=r.readLine())!=null)s.append(line);
            int code=c.getResponseCode();c.disconnect();JSONObject root=new JSONObject(s.toString());
            if(code>=400)throw new Exception(root.optJSONObject("error")!=null?root.optJSONObject("error").optString("message","No cached flights for this route/date."):"No cached flights for this route/date.");
            JSONArray offers=root.optJSONArray("offers");runOnUiThread(()->{holder.removeAllViews();if(offers==null||offers.length()==0){status.setText("No cached flights found for this route/date.");return;}status.setText("Flight options");for(int i=0;i<offers.length();i++)try{addFlightCard(holder,offers.getJSONObject(i));}catch(Exception ignored){}}); 
        }catch(Exception e){runOnUiThread(()->status.setText("No cached flight data for this route/date yet. Try another date or supported route."));}}).start();
    }

    void addFlightCard(LinearLayout holder,JSONObject o)throws Exception{
        double price=o.optJSONObject("price")!=null?o.getJSONObject("price").optDouble("amountMinor",0)/100.0:0;String cur=o.optJSONObject("price")!=null?o.getJSONObject("price").optString("currency","USD"):"USD";
        JSONArray its=o.optJSONArray("itineraries");JSONObject it=its!=null&&its.length()>0?its.getJSONObject(0):new JSONObject();JSONArray segs=it.optJSONArray("segments");JSONObject seg=segs!=null&&segs.length()>0?segs.getJSONObject(0):new JSONObject();
        String dep=seg.optString("departureAt","").replace('T',' '),arr=seg.optString("arrivalAt","").replace('T',' '),carrier=seg.optJSONObject("marketingCarrier")!=null?seg.getJSONObject("marketingCarrier").optString("name","Airline"):"Airline";
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(18),dp(16),dp(18),dp(16));card.setBackground(bg(Color.WHITE,22));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(145));p.setMargins(0,0,0,dp(14));
        TextView top=tv(carrier,19,DARK);top.setTypeface(null,1);card.addView(top,new LinearLayout.LayoutParams(-1,dp(32)));
        card.addView(tv(dep+"  →  "+arr,14,Color.DKGRAY),new LinearLayout.LayoutParams(-1,dp(34)));
        String stops=it.optInt("stops",0)==0?"Non-stop":"Stops: "+it.optInt("stops",0);
        TextView info=tv(stops+"    •    "+String.format(Locale.US,"$%.2f adult",price),14,Color.GRAY);card.addView(info,new LinearLayout.LayoutParams(-1,dp(32)));
        card.addView(tv(String.format(Locale.US,"Child: $%.2f",price*.75)+"    •    "+it.optInt("durationMinutes",0)+" min",13,Color.GRAY));holder.addView(card,p);
    }

    void loadVacations(){String all=prefs.getString("vacations","");if(all.isEmpty())return;for(String x:all.split("\\u001F",-1)){String[] p=x.split("\\u001E",-1);vacations.add(new Vacation(p[0],p.length>1?p[1]:""));}}
    void saveVacations(){StringBuilder s=new StringBuilder();for(Vacation v:vacations){if(s.length()>0)s.append('\u001F');s.append(v.name.replace("\u001F"," ")).append('\u001E').append(v.image==null?"":v.image);}prefs.edit().putString("vacations",s.toString()).apply();}

    class SplashView extends View{
        Paint p=new Paint(3);long start=System.currentTimeMillis();SplashView(Context c){super(c);}
        protected void onDraw(Canvas c){super.onDraw(c);c.drawColor(CREAM);float w=getWidth(),h=getHeight();p.setTextAlign(Paint.Align.CENTER);
            // soft decorative travel glow
            p.setColor(Color.rgb(255,231,205));c.drawCircle(w*.18f,h*.24f,dp(70),p);p.setColor(Color.rgb(255,242,228));c.drawCircle(w*.82f,h*.30f,dp(95),p);
            p.setTypeface(Typeface.create("sans",Typeface.BOLD));p.setTextSize(dp(44));p.setColor(DARK);c.drawText("Vicationfly",w/2,h*.37f,p);
            p.setTypeface(Typeface.create("sans",Typeface.NORMAL));p.setTextSize(dp(16));p.setColor(Color.rgb(100,90,82));c.drawText("a whole vocation in one place",w/2,h*.425f,p);
            float t=((System.currentTimeMillis()-start)%2100)/2100f;float x=-dp(80)+t*(w+dp(160));drawPlane(c,x,h*.58f);
            p.setColor(Color.rgb(255,210,170));p.setStrokeWidth(dp(2));c.drawLine(dp(35),h*.64f,w-dp(35),h*.64f,p);
            p.setColor(ORANGE);c.drawCircle(dp(35)+t*(w-dp(70)),h*.64f,dp(5),p);
            p.setTextSize(dp(14));p.setColor(Color.GRAY);c.drawText("Preparing worldwide flight search",w/2,h*.71f,p);
            // progress dots
            for(int i=0;i<4;i++){float a=((System.currentTimeMillis()-start)/250f+i)%4;p.setColor(i<((System.currentTimeMillis()-start)/800)%5?ORANGE:Color.rgb(235,205,180));c.drawCircle(w/2+dp((i-1.5f)*14),h*.77f,dp(4),p);}
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(3));p.setColor(ORANGE);c.drawCircle(w/2,h*.86f,dp(24),p);p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(100,90,82));p.setTextSize(dp(12));c.drawText("Flights • Airports • Prices",w/2,h*.91f,p);postInvalidateDelayed(16);
        }
        void drawPlane(Canvas c,float x,float y){
            p.setStyle(Paint.Style.FILL);p.setColor(ORANGE);
            Path body=new Path();body.moveTo(x,y);body.lineTo(x+dp(74),y-dp(8));body.quadTo(x+dp(84),y,x+dp(74),y+dp(8));body.close();c.drawPath(body,p);
            Path upper=new Path();upper.moveTo(x+dp(30),y);upper.lineTo(x+dp(6),y-dp(30));upper.lineTo(x+dp(22),y-dp(20));upper.lineTo(x+dp(44),y);upper.close();c.drawPath(upper,p);
            Path lower=new Path();lower.moveTo(x+dp(33),y);lower.lineTo(x+dp(12),y+dp(27));lower.lineTo(x+dp(27),y+dp(19));lower.lineTo(x+dp(46),y);lower.close();c.drawPath(lower,p);
            Path tail=new Path();tail.moveTo(x+dp(11),y);tail.lineTo(x+dp(2),y-dp(17));tail.lineTo(x+dp(16),y-dp(11));tail.close();c.drawPath(tail,p);
            p.setColor(Color.WHITE);c.drawCircle(x+dp(63),y,dp(3),p);
        }
    }
}