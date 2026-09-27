package ua.anprauto;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.graphics.ImageFormat;
import android.graphics.YuvImage;
import android.graphics.Matrix;
import android.os.SystemClock;
import androidx.exifinterface.media.ExifInterface;
import java.io.ByteArrayOutputStream;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.util.Size;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** Offline preview build with bundled ML Kit OCR. Full YOLO detector pending. */
public class MainActivity extends ComponentActivity {
    private static final int CAMERA_PERMISSION = 41;
    private final Pattern fullPlate = Pattern.compile("^[ABCEHIKMOPTX]{2}[0-9]{4}[ABCEHIKMOPTX]{2}$");
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private PreviewView preview;
    private BoxOverlay overlay;
    private TextView status;
    private TextView captureIndicator;
    private Rect lastPlateBox;
    private int lastImageWidth=0,lastImageHeight=0;
    private ImageView snapshot;
    private FrameLayout root;
    private ImageCapture imageCapture;
    private com.google.mlkit.vision.text.TextRecognizer recognizer;
    private PlateDetector detector;
    private volatile long lastAnalyzed=0;
    private String candidate="";
    private int candidateCount=0;
    private java.util.Map<String,Long> savedNumbers = new java.util.HashMap<>();
    private volatile boolean processing = false;
    private volatile boolean saving = false;
    private String previousPlate = "";
    private long previousShot = 0;
    private boolean started = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(12,19,27));
        preview = new PreviewView(this);
        preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        root.addView(preview, new FrameLayout.LayoutParams(-1,-1));
        overlay = new BoxOverlay();
        root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(18,12,18,12);
        bar.setBackgroundColor(0xDD07131E);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(19);
        status.setText("ANPR AUTO · OFFLINE OCR\nОчікування камери...");
        bar.addView(status);
        captureIndicator=new TextView(this);
        captureIndicator.setText("● ГОТОВО ДО ФОТО");
        captureIndicator.setTextColor(0xFF94A3B8);
        captureIndicator.setTextSize(22);
        captureIndicator.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        captureIndicator.setPadding(2,12,2,10);
        bar.addView(captureIndicator);
        TextView details = new TextView(this);
        details.setText("Зелена: повний номер · жовта: частковий · червона: нечіткий\nФото HIGH · пауза 1 с · антидубль 30 с");
        details.setTextSize(12);
        details.setTextColor(0xFFB9C9D9);
        bar.addView(details);
        FrameLayout.LayoutParams top = new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);
        root.addView(bar, top);
        snapshot = new ImageView(this);
        snapshot.setScaleType(ImageView.ScaleType.FIT_CENTER);
        snapshot.setBackgroundColor(0xE9000000);
        snapshot.setVisibility(View.GONE);
        FrameLayout.LayoutParams thumb = new FrameLayout.LayoutParams(-1,-1);
        root.addView(snapshot,thumb);
        setContentView(root);
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) startCamera();
        else requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION);
    }
    @Override public void onRequestPermissionsResult(int request,@NonNull String[] permissions,@NonNull int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==CAMERA_PERMISSION && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) startCamera();
        else status.setText("Надайте дозвіл на камеру в налаштуваннях Android");
    }
    private void startCamera(){
        if(started)return;
        started=true;
        ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);
        future.addListener(()->{
            try {
                if(detector==null)detector=new PlateDetector(this);
                ProcessCameraProvider provider=future.get();
                ResolutionSelector previewResolution=new ResolutionSelector.Builder().setResolutionStrategy(
                  new ResolutionStrategy(new Size(1920,1080),ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build();
                ResolutionSelector photoResolution=new ResolutionSelector.Builder().setResolutionStrategy(
                  new ResolutionStrategy(new Size(4000,3000),ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build();
                Preview p = new Preview.Builder().setResolutionSelector(previewResolution).build();
                p.setSurfaceProvider(preview.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setJpegQuality(98).setResolutionSelector(photoResolution).build();
                ImageAnalysis analysis=new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(new ResolutionSelector.Builder().setResolutionStrategy(
                        new ResolutionStrategy(new Size(1280,720),ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                    .build();
                analysis.setAnalyzer(io,this::analyse);
                provider.unbindAll();
                provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,p,analysis,imageCapture);
                status.setText("ANPR AUTO · OFFLINE OCR\nКамера працює");
            } catch(Exception e){status.setText("Помилка камери: "+e.getMessage());}
        },ContextCompat.getMainExecutor(this));
    }
    // CameraX still images are captured separately at sensor resolution, not from the preview Bitmap.
    private void analyse(@NonNull ImageProxy image){
        long now=SystemClock.uptimeMillis();
        if(processing||now-lastAnalyzed<220){image.close();return;}
        processing=true;lastAnalyzed=now;
        Bitmap frame;
        try{frame=toBitmap(image);}catch(Exception e){image.close();processing=false;return;}
        image.close();
        if(frame==null){processing=false;return;}
        Rect plate=detector!=null?detector.detect(frame):null;
        if(plate==null){
            main.post(()->{overlay.update(null,Color.RED,frame.getWidth(),frame.getHeight());
                status.setText("ANPR AUTO · OFFLINE AI\\nПошук номерів...");});
            frame.recycle();processing=false;return;
        }
        int padX=Math.max(4,plate.width()/10),padY=Math.max(3,plate.height()/5);
        Rect cropBox=new Rect(Math.max(0,plate.left-padX),Math.max(0,plate.top-padY),
                Math.min(frame.getWidth(),plate.right+padX),Math.min(frame.getHeight(),plate.bottom+padY));
        Bitmap crop=Bitmap.createBitmap(frame,cropBox.left,cropBox.top,cropBox.width(),cropBox.height());
        int sharpness=sharpness(crop);
        final int frameW=frame.getWidth(),frameH=frame.getHeight();
        frame.recycle();
        InputImage input=InputImage.fromBitmap(crop,0);
        recognizer.process(input).addOnSuccessListener(result->{
            String best="";
            // OCR may split number into multiple lines on two-row EU plates.
            for(Text.TextBlock b:result.getTextBlocks()){
                String joined=normalize(b.getText());
                if(joined.length()>best.length()&&joined.length()<=12)best=joined;
                for(Text.Line l:b.getLines()){
                    String candidate=normalize(l.getText());
                    if(candidate.length()>best.length()&&candidate.length()<=12)best=candidate;
                }
            }
            String value=best;
            boolean plausible=value.length()>=6&&value.length()<=10&&countDigits(value)>=2
                    &&value.matches("[A-Z0-9]+")&&countLetters(value)>=1;
            if(value.equals(this.candidate)&&plausible)candidateCount++;
            else{this.candidate=value;candidateCount=1;}
            int color=plausible&&candidateCount>=2&&sharpness>=35?Color.GREEN:
                (!value.isEmpty()&&sharpness>=15?Color.YELLOW:Color.RED);
            main.post(()->{
                overlay.update(plate,color,frameW,frameH);
                if(color==Color.GREEN)status.setText("ANPR AUTO · ЗНАЙДЕНО\\n"+value);
                else if(color==Color.YELLOW)status.setText("ANPR AUTO · ЧАСТКОВО\\n"+value);
                else status.setText("ANPR AUTO · РОЗМИТО\\nШукаємо чіткіший кадр...");
                if(color!=Color.RED&&!saving){
                    long time=System.currentTimeMillis();
                    Long last=savedNumbers.get(value);
                    if(last==null||time-last>=30000){
                        savedNumbers.put(value,time);
                        lastPlateBox=new Rect(plate);lastImageWidth=frameW;lastImageHeight=frameH;
                        savePhoto(value,color);
                    }
                }
            });
        }).addOnFailureListener(e->main.post(()->status.setText("Помилка OCR: "+e.getMessage())))
          .addOnCompleteListener(task->{crop.recycle();processing=false;});
    }
    /** Convert camera YUV_420_888 safely with row/pixel stride and rotate to screen orientation. */
    private Bitmap toBitmap(ImageProxy image) throws Exception {
        int width=image.getWidth(),height=image.getHeight();
        byte[] nv21=new byte[width*height*3/2];
        ImageProxy.PlaneProxy[] planes=image.getPlanes();
        java.nio.ByteBuffer y=planes[0].getBuffer(),u=planes[1].getBuffer(),v=planes[2].getBuffer();
        for(int row=0;row<height;row++){
            int at=row*planes[0].getRowStride();
            for(int col=0;col<width;col++)nv21[row*width+col]=y.get(at+col*planes[0].getPixelStride());
        }
        int chromaOffset=width*height;
        for(int row=0;row<height/2;row++){
            for(int col=0;col<width/2;col++){
                int uv=chromaOffset+row*width+col*2;
                nv21[uv]=v.get(row*planes[2].getRowStride()+col*planes[2].getPixelStride());
                nv21[uv+1]=u.get(row*planes[1].getRowStride()+col*planes[1].getPixelStride());
            }
        }
        YuvImage yuv=new YuvImage(nv21,ImageFormat.NV21,width,height,null);
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0,0,width,height),88,output);
        byte[] jpg=output.toByteArray();
        Bitmap bitmap=BitmapFactory.decodeByteArray(jpg,0,jpg.length);
        int rotation=image.getImageInfo().getRotationDegrees();
        if(rotation!=0){Matrix m=new Matrix();m.postRotate(rotation);
            Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),m,true);
            bitmap.recycle();return rotated;
        }
        return bitmap;
    }
    /** Fast local blur estimate. Thresholds are initial heuristics to calibrate on real footage. */
    private int sharpness(Bitmap image) {
        int w=image.getWidth(),h=image.getHeight();
        if(w<10||h<10)return 0;
        Bitmap sample=Bitmap.createScaledBitmap(image,Math.min(192,w),Math.min(64,h),true);
        int sw=sample.getWidth(),sh=sample.getHeight();
        int[] px=new int[sw*sh];sample.getPixels(px,0,sw,0,0,sw,sh);
        long energy=0;int count=0;
        for(int y=1;y<sh-1;y+=2)for(int x=1;x<sw-1;x+=2){
            int center=luma(px[y*sw+x]);
            int edge=Math.abs(4*center-luma(px[y*sw+x-1])-luma(px[y*sw+x+1])
                    -luma(px[(y-1)*sw+x])-luma(px[(y+1)*sw+x]));
            energy+=edge;count++;
        }
        if(sample!=image)sample.recycle();
        return count==0?0:(int)Math.min(100,energy/count*2);
    }
    private int luma(int p){return (((p>>16)&255)*77+((p>>8)&255)*150+(p&255)*29)>>8;}
    private int countLetters(String s){int n=0;for(int i=0;i<s.length();i++)if(Character.isLetter(s.charAt(i)))n++;return n;}
    private String normalize(String text){
        return text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]","");
    }
    private int countDigits(String s){int n=0;for(int i=0;i<s.length();i++)if(Character.isDigit(s.charAt(i)))n++;return n;}
    private void savePhoto(String plate,int tint){
        if(imageCapture==null)return;
        saving=true;
        captureIndicator.setText("● ФОТОГРАФУВАННЯ: "+plate);
        captureIndicator.setTextColor(tint);
        captureIndicator.setBackgroundColor(0xDD101820);
        main.postDelayed(()->{captureIndicator.setText("● ГОТОВО ДО ФОТО");captureIndicator.setTextColor(0xFF94A3B8);},1000);
        File folder=new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),"ANPR");
        if(!folder.exists()&&!folder.mkdirs()){saving=false;return;}
        String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.ROOT).format(new Date());
        File target=new File(folder,stamp+"_"+plate+".jpg");
        imageCapture.takePicture(new ImageCapture.OutputFileOptions.Builder(target).build(),
            io,new ImageCapture.OnImageSavedCallback(){
                @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults output){
                    saving=false;
                    main.post(()->{captureIndicator.setText("● ЗБЕРЕЖЕНО: "+plate);captureIndicator.setTextColor(tint);});
                    io.execute(()->annotatePhoto(target,plate,tint));
                    main.post(()->Toast.makeText(MainActivity.this,"Фото збережено: "+plate,Toast.LENGTH_SHORT).show());
                }
                @Override public void onError(@NonNull androidx.camera.core.ImageCaptureException e){
                    saving=false;main.post(()->{captureIndicator.setText("● ПОМИЛКА ФОТО");captureIndicator.setTextColor(Color.RED);status.setText("Помилка запису: "+e.getMessage());});
                }
            });
    }
    /** Preserve raw full-resolution JPEG and draw the rectangle on a separately saved corrected image. */
    private void annotatePhoto(File target,String plate,int tint){
        Bitmap source=null,rotated=null,edited=null;
        try {
            File original=new File(target.getParentFile(),target.getName().replace(".jpg","-original.jpg"));
            try(java.io.InputStream in=new java.io.FileInputStream(target);
                java.io.OutputStream out=new java.io.FileOutputStream(original)){
                byte[] buffer=new byte[65536];int n;
                while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            }
            int orientation=new ExifInterface(original.getAbsolutePath()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);
            source=BitmapFactory.decodeFile(original.getAbsolutePath());
            if(source==null)return;
            Matrix matrix=new Matrix();
            if(orientation==ExifInterface.ORIENTATION_ROTATE_90)matrix.postRotate(90);
            else if(orientation==ExifInterface.ORIENTATION_ROTATE_180)matrix.postRotate(180);
            else if(orientation==ExifInterface.ORIENTATION_ROTATE_270)matrix.postRotate(270);
            if(orientation!=ExifInterface.ORIENTATION_NORMAL){
                rotated=Bitmap.createBitmap(source,0,0,source.getWidth(),source.getHeight(),matrix,true);
            } else rotated=source;
            edited=rotated.copy(Bitmap.Config.ARGB_8888,true);
            Canvas c=new Canvas(edited);
            Rect plateBox=detector!=null?detector.detect(edited):null;
            float scale=Math.max(1,edited.getWidth()/1100f);
            Paint pen=new Paint(3);
            pen.setColor(tint);
            pen.setStyle(Paint.Style.STROKE);
            pen.setStrokeWidth(6*scale);
            if(plateBox!=null)c.drawRoundRect(new RectF(plateBox),9*scale,9*scale,pen);
            pen.setStyle(Paint.Style.FILL);
            Paint panel=new Paint(3);panel.setColor(0xDF061420);
            float labelWidth=Math.min(edited.getWidth()-20*scale,760*scale);
            float y=plateBox==null?24*scale:Math.max(24*scale,plateBox.top-110*scale);
            c.drawRoundRect(new RectF(24*scale,y,labelWidth,y+100*scale),12*scale,12*scale,panel);
            pen.setTypeface(Typeface.DEFAULT_BOLD);pen.setTextSize(45*scale);
            c.drawText("● "+plate,45*scale,y+67*scale,pen);
            try(java.io.FileOutputStream stream=new java.io.FileOutputStream(target)){
                edited.compress(Bitmap.CompressFormat.JPEG,96,stream);
            }
            publishToGallery(target);
            Bitmap display=BitmapFactory.decodeFile(target.getAbsolutePath(),new BitmapFactory.Options(){{
                inSampleSize=2;
            }});
            if(display!=null)main.post(()->{
                snapshot.setImageBitmap(display);
                snapshot.setVisibility(View.VISIBLE);
                main.postDelayed(()->{snapshot.setVisibility(View.GONE);snapshot.setImageDrawable(null);display.recycle();},1000);
            });
        }catch(Exception e){android.util.Log.w("ANPR","Photo annotation failed",e);}
        finally {
            if(edited!=null&&!edited.isRecycled())edited.recycle();
            if(rotated!=null&&rotated!=source&&!rotated.isRecycled())rotated.recycle();
            if(source!=null&&!source.isRecycled())source.recycle();
        }
    }
    /** Publish annotated JPEG in Android's visible gallery, without exposing the raw backup. */
    private void publishToGallery(File image){
        try{
            ContentValues values=new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,image.getName());
            values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/ANPR AUTO");
            values.put(MediaStore.Images.Media.IS_PENDING,1);
            android.net.Uri uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
            if(uri==null)return;
            try(java.io.InputStream in=new java.io.FileInputStream(image);
                java.io.OutputStream out=getContentResolver().openOutputStream(uri)){
                if(out==null)return;
                byte[] buffer=new byte[65536];int n;
                while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            }
            values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);
            getContentResolver().update(uri,values,null,null);
        }catch(Exception e){android.util.Log.e("ANPR","Gallery export failed",e);}
    }
    @Override public void onDestroy(){
        if(detector!=null)detector.close();
        recognizer.close();io.shutdown();super.onDestroy();
    }
    private final class BoxOverlay extends View{
        private final Paint border=new Paint(3);
        private final Paint label=new Paint(3);
        private Rect box=null;
        private int shade=Color.GREEN;
        private int sourceWidth=1,sourceHeight=1;
        BoxOverlay(){super(MainActivity.this);border.setStyle(Paint.Style.STROKE);border.setStrokeWidth(7);label.setTextSize(34);label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);}
        void update(Rect r,int c,int w,int h){box=r;shade=c;sourceWidth=Math.max(1,w);sourceHeight=Math.max(1,h);invalidate();}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            if(box==null)return;
            // InputImage coordinates are rotation-adjusted. PreviewView uses FILL_CENTER.
            float scale=Math.min(getWidth()/(float)sourceWidth,getHeight()/(float)sourceHeight);
            float dx=(getWidth()-sourceWidth*scale)/2f,dy=(getHeight()-sourceHeight*scale)/2f;
            RectF b=new RectF(dx+box.left*scale,dy+box.top*scale,dx+box.right*scale,dy+box.bottom*scale);
            border.setColor(shade);c.drawRoundRect(b,9,9,border);
            label.setColor(shade);c.drawText(shade==Color.GREEN?"РОЗПІЗНАНО":shade==Color.YELLOW?"ЧАСТКОВО":"НЕЧІТКО",Math.max(10,b.left),Math.max(42,b.top-12),label);
        }
    }
}
