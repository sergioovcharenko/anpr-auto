package ua.anprauto;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;
import ai.onnxruntime.*;
import java.io.*;
import java.nio.FloatBuffer;
import java.util.*;

/** Offline multi-plate detector. Letterbox preprocessing; per-model output validation + NMS. */
public final class OfflinePlateDetector implements AutoCloseable {
    public static final class Hit {
        public final Rect rect;
        public final float confidence;
        Hit(Rect r,float c){rect=r;confidence=c;}
    }
    private static final int INPUT=640;
    private final OrtEnvironment env=OrtEnvironment.getEnvironment();
    private final List<OrtSession> sessions=new ArrayList<>();
    private final List<String> inputs=new ArrayList<>();
    private final float[] rgb=new float[3*INPUT*INPUT];
    private boolean loggedShapes=false;

    public OfflinePlateDetector(Context context){
        load(context,"plate-europe.onnx");
        load(context,"plate-ukraine.onnx");
    }
    private void load(Context context,String asset){
        try{
            File f=new File(context.getFilesDir(),asset);
            if(!f.exists()||f.length()<1000000){
                try(InputStream in=context.getAssets().open(asset);FileOutputStream out=new FileOutputStream(f)){
                    byte[] buf=new byte[65536];int n;
                    while((n=in.read(buf))!=-1)out.write(buf,0,n);
                }
            }
            OrtSession.SessionOptions opts=new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(2);
            OrtSession s=env.createSession(f.getAbsolutePath(),opts);
            sessions.add(s);
            inputs.add(s.getInputNames().iterator().next());
            Log.i("ANPR_AI","Loaded "+asset+" ("+f.length()+" bytes)");
        }catch(Exception e){Log.e("ANPR_AI","Model not loaded: "+asset,e);}
    }
    public boolean available(){return !sessions.isEmpty();}
    public synchronized List<Hit> detect(Bitmap inputBitmap){
        if(inputBitmap==null||sessions.isEmpty())return Collections.emptyList();
        final int origW=inputBitmap.getWidth(),origH=inputBitmap.getHeight();
        if(origW<=0||origH<=0)return Collections.emptyList();
        final float scale=Math.min(INPUT/(float)origW,INPUT/(float)origH);
        final int scaledW=Math.max(1,Math.round(origW*scale)),scaledH=Math.max(1,Math.round(origH*scale));
        final int px=(INPUT-scaledW)/2,py=(INPUT-scaledH)/2;
        Bitmap small=Bitmap.createScaledBitmap(inputBitmap,scaledW,scaledH,true);
        Bitmap canvas=Bitmap.createBitmap(INPUT,INPUT,Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c=new android.graphics.Canvas(canvas);
        c.drawColor(android.graphics.Color.rgb(114,114,114));
        c.drawBitmap(small,px,py,null);
        int[] pixels=new int[INPUT*INPUT];canvas.getPixels(pixels,0,INPUT,0,0,INPUT,INPUT);
        if(small!=inputBitmap)small.recycle();canvas.recycle();
        final int area=INPUT*INPUT;
        for(int i=0;i<area;i++){
            int v=pixels[i];
            rgb[i]=((v>>16)&255)/255f;
            rgb[area+i]=((v>>8)&255)/255f;
            rgb[area*2+i]=(v&255)/255f;
        }
        List<Hit> candidates=new ArrayList<>();
        try(OnnxTensor tensor=OnnxTensor.createTensor(env,FloatBuffer.wrap(rgb),new long[]{1,3,INPUT,INPUT})){
            for(int m=0;m<sessions.size();m++){
                try(OrtSession.Result out=sessions.get(m).run(Collections.singletonMap(inputs.get(m),tensor))){
                    for(Map.Entry<String,OnnxValue> entry:out){
                        OnnxValue value=entry.getValue();
                        if(!(value instanceof OnnxTensor))continue;
                        Object raw=((OnnxTensor)value).getValue();
                        if(!loggedShapes)Log.i("ANPR_AI","Output "+entry.getKey()+": "+raw.getClass().getName());
                        decode(raw,px,py,scale,origW,origH,candidates);
                    }
                }catch(Exception ex){Log.e("ANPR_AI","Inference for model "+m+" failed",ex);}
            }
            loggedShapes=true;
        }catch(Exception ex){Log.e("ANPR_AI","Tensor error",ex);}
        candidates.sort((a,b)->Float.compare(b.confidence,a.confidence));
        List<Hit> kept=new ArrayList<>();
        for(Hit hit:candidates){
            boolean duplicate=false;
            for(Hit prior:kept)if(iou(hit.rect,prior.rect)>.45f){duplicate=true;break;}
            if(!duplicate)kept.add(hit);
            if(kept.size()>=12)break;
        }
        return kept;
    }
    private void decode(Object raw,int padX,int padY,float scale,int width,int height,List<Hit> hits){
        if(!(raw instanceof float[][][]))return;
        float[][][] batch=(float[][][])raw;
        if(batch.length==0||batch[0].length==0)return;
        float[][] matrix=batch[0];
        boolean channelsFirst=matrix.length<=128 && matrix[0].length>128;
        int num=channelsFirst?matrix[0].length:matrix.length;
        int cols=channelsFirst?matrix.length:matrix[0].length;
        // Reject unsupported output layouts rather than drawing a huge false-positive box.
        if(cols<5||cols>128||num>100000)return;
        for(int i=0;i<num;i++){
            float[] v=new float[Math.min(cols,8)];
            for(int k=0;k<v.length;k++)v[k]=channelsFirst?matrix[k][i]:matrix[i][k];
            float conf;
            if(cols==5)conf=v[4];                     // one class, no separate objectness
            else if(cols==6&&num<=1000)conf=v[4];      // end-to-end [x1,y1,x2,y2,confidence,class]
            else if(cols==6)conf=v[4]*v[5];            // classic YOLO objectness * class
            else{
                float max=0f;for(int k=4;k<Math.min(cols,8);k++)max=Math.max(max,v[k]);
                conf=max;
            }
            if(!Float.isFinite(conf)||conf<.55f||conf>1.001f)continue;
            boolean xyxy=cols==6&&num<=1000&&v[2]>v[0]&&v[3]>v[1];
            float x0=xyxy?v[0]:v[0]-v[2]/2f;
            float y0=xyxy?v[1]:v[1]-v[3]/2f;
            float x1=xyxy?v[2]:v[0]+v[2]/2f;
            float y1=xyxy?v[3]:v[1]+v[3]/2f;
            // Some exports emit normalized 0..1 coordinates.
            if(Math.max(Math.max(Math.abs(x0),Math.abs(y0)),Math.max(Math.abs(x1),Math.abs(y1)))<=1.5f){
                x0*=INPUT;y0*=INPUT;x1*=INPUT;y1*=INPUT;
            }
            if(!Float.isFinite(x0)||!Float.isFinite(y0)||!Float.isFinite(x1)||!Float.isFinite(y1))continue;
            int left=clamp(Math.round((x0-padX)/scale),0,width);
            int top=clamp(Math.round((y0-padY)/scale),0,height);
            int right=clamp(Math.round((x1-padX)/scale),0,width);
            int bottom=clamp(Math.round((y1-padY)/scale),0,height);
            int w=right-left,h=bottom-top;
            if(w<18||h<7||w>width*.70||h>height*.40||w/(float)h<1.1f||w/(float)h>7.0f)continue;
            hits.add(new Hit(new Rect(left,top,right,bottom),conf));
        }
    }
    private static int clamp(int v,int low,int high){return Math.min(high,Math.max(low,v));}
    private static float iou(Rect a,Rect b){
        int left=Math.max(a.left,b.left),right=Math.min(a.right,b.right);
        int top=Math.max(a.top,b.top),bottom=Math.min(a.bottom,b.bottom);
        int overlap=Math.max(0,right-left)*Math.max(0,bottom-top);
        int union=a.width()*a.height()+b.width()*b.height()-overlap;
        return union>0?overlap/(float)union:0f;
    }
    @Override public void close(){for(OrtSession s:sessions){try{s.close();}catch(Exception ignored){}}}
}
