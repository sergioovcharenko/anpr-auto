package ua.anprauto;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;

/** Offline European YOLO model. Returns a plate rectangle in original bitmap coordinates. */
public final class PlateDetector implements AutoCloseable {
    private static final int SIZE=640;
    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final int[] pixels = new int[SIZE*SIZE];
    private final float[] data = new float[SIZE*SIZE*3];
    private boolean warned=false;
    public PlateDetector(Context context) throws Exception {
        File file=new File(context.getFilesDir(),"plate-europe.onnx");
        if(!file.exists()) {
            try(InputStream in=context.getAssets().open("plate-europe.onnx");
                FileOutputStream out=new FileOutputStream(file)){
                byte[] buf=new byte[65536];int n;
                while((n=in.read(buf))!=-1)out.write(buf,0,n);
            }
        }
        env=OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts=new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(2);
        session=env.createSession(file.getAbsolutePath(),opts);
        inputName=session.getInputNames().iterator().next();
        Log.i("ANPR","ONNX model loaded: "+file.length()+" bytes");
    }
    public synchronized Rect detect(Bitmap source){
        if(source==null)return null;
        Bitmap scaled=Bitmap.createScaledBitmap(source,SIZE,SIZE,true);
        scaled.getPixels(pixels,0,SIZE,0,0,SIZE,SIZE);
        if(scaled!=source)scaled.recycle();
        for(int i=0;i<pixels.length;i++){
            int p=pixels[i];
            data[i]=((p>>16)&255)/255f;
            data[i+SIZE*SIZE]=((p>>8)&255)/255f;
            data[i+2*SIZE*SIZE]=(p&255)/255f;
        }
        try(OnnxTensor tensor=OnnxTensor.createTensor(env,FloatBuffer.wrap(data),new long[]{1,3,SIZE,SIZE});
            OrtSession.Result result=session.run(Collections.singletonMap(inputName,tensor))){
            Object raw=result.get(0).getValue();
            if(!(raw instanceof float[][][]))return null;
            float[][][] out=(float[][][])raw;
            if(out.length==0 || out[0].length==0)return null;
            // YOLOv12/YOLOv8 ONNX: either [1,5,8400] or [1,8400,5].
            float[][] layer=out[0];
            boolean channelFirst=layer.length<=128;
            int channels=channelFirst?layer.length:layer[0].length;
            int boxes=channelFirst?layer[0].length:layer.length;
            if(channels<5)return null;
            float best=.44f;float x=0,y=0,w=0,h=0;
            for(int i=0;i<boxes;i++){
                float conf=channelFirst?layer[4][i]:layer[i][4];
                if(conf<=best)continue;
                float xx=channelFirst?layer[0][i]:layer[i][0];
                float yy=channelFirst?layer[1][i]:layer[i][1];
                float ww=channelFirst?layer[2][i]:layer[i][2];
                float hh=channelFirst?layer[3][i]:layer[i][3];
                if(ww<8||hh<4||ww>SIZE||hh>SIZE)continue;
                best=conf;x=xx;y=yy;w=ww;h=hh;
            }
            if(best<=.44f)return null;
            float sx=source.getWidth()/(float)SIZE,sy=source.getHeight()/(float)SIZE;
            int left=Math.max(0,Math.round((x-w/2)*sx));
            int top=Math.max(0,Math.round((y-h/2)*sy));
            int right=Math.min(source.getWidth(),Math.round((x+w/2)*sx));
            int bottom=Math.min(source.getHeight(),Math.round((y+h/2)*sy));
            if(right-left<16||bottom-top<8)return null;
            return new Rect(left,top,right,bottom);
        }catch(Exception e){
            if(!warned){Log.e("ANPR","Detector runtime failed",e);warned=true;}
            return null;
        }
    }
    @Override public void close(){try{session.close();}catch(Exception ignored){}}
}
