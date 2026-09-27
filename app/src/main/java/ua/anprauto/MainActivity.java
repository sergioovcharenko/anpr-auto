package ua.anprauto;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.BitmapFactory;
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
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
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
                ProcessCameraProvider provider=future.get();
                ResolutionSelector previewResolution=new ResolutionSelector.Builder().setResolutionStrategy(
                  new ResolutionStrategy(new Size(1920,1080),ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build();
                ResolutionSelector photoResolution=new ResolutionSelector.Builder().setResolutionStrategy(
                  new ResolutionStrategy(new Size(4000,3000),ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build();
                Preview p = new Preview.Builder().setResolutionSelector(previewResolution).build();
                p.setSurfaceProvider(preview.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setJpegQuality(98).setResolutionSelector(photoResolution).build();
                ImageAnalysis analysis=new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(io,this::analyse);
                provider.unbindAll();
                provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,p,analysis,imageCapture);
                status.setText("ANPR AUTO · OFFLINE OCR\nКамера працює");
            } catch(Exception e){status.setText("Помилка камери: "+e.getMessage());}
        },ContextCompat.getMainExecutor(this));
    }
    private void analyse(@NonNull ImageProxy image){
        if(processing){image.close();return;}
        android.media.Image media=image.getImage();
        if(media==null){image.close();return;}
        processing=true;
        try {
            InputImage input=InputImage.fromMediaImage(media,image.getImageInfo().getRotationDegrees());
            recognizer.process(input).addOnSuccessListener(result->{
                String found="";
                Rect box=null;
                int color=Color.RED;
                String partial="";
                Rect partialBox=null;
                for(Text.TextBlock block:result.getTextBlocks()){
                    for(Text.Line line:block.getLines()){
                        String clean=normalize(line.getText());
                        if(fullPlate.matcher(clean).matches()){
                            found=clean;box=line.getBoundingBox();color=Color.GREEN;break;
                        }
                        if(clean.length()>=4 && clean.length()<=10 && clean.matches("[A-Z0-9]+") && countDigits(clean)>=2){
                            if(clean.length()>partial.length()){partial=clean;partialBox=line.getBoundingBox();}
                        }
                    }
                    if(!found.isEmpty())break;
                }
                if(found.isEmpty() && !partial.isEmpty()){found=partial;box=partialBox;color=Color.YELLOW;}
                if(found.isEmpty() && !result.getTextBlocks().isEmpty()){
                    Text.TextBlock b=result.getTextBlocks().get(0);
                    box=b.getBoundingBox();
                    color=Color.RED;
                }
                final String value=found;
                final Rect rect=box;
                final int tint=color;
                final int width=input.getWidth(),height=input.getHeight();
                main.post(()->{
                    overlay.update(rect,tint,width,height);
                    if(rect!=null && !value.isEmpty()) {lastPlateBox=new Rect(rect);lastImageWidth=width;lastImageHeight=height;}
                    if(rect==null){status.setText("ANPR AUTO · OFFLINE OCR\nПошук номерів...");}
                    else if(value.isEmpty()){status.setText("ANPR AUTO · НЕЧІТКО\nПовторна спроба...");}
                    else status.setText("ANPR AUTO · "+(tint==Color.GREEN?"ЗНАЙДЕНО":"ЧАСТКОВО")+"\n"+value);
                    if(!value.isEmpty() && !saving && (tint==Color.GREEN||tint==Color.YELLOW)){
                        long now=System.currentTimeMillis();
                        if(!value.equals(previousPlate) || now-previousShot>=30000){
                            previousPlate=value;previousShot=now;savePhoto(value,tint);
                        }
                    }
                });
            }).addOnFailureListener(e->main.post(()->status.setText("Помилка OCR: "+e.getMessage())))
              .addOnCompleteListener(task->{processing=false;image.close();});
        }catch(Exception e){processing=false;image.close();}
    }
    private String normalize(String text){
        return text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]","");
    }
    private int countDigits(String s){int n=0;for(int i=0;i<s.length();i++)if(Character.isDigit(s.charAt(i)))n++;return n;}
    private void savePhoto(String plate,int tint){
        if(imageCapture==null)return;
        saving=true;
        captureIndicator.setText("● ФОТО ЗРОБЛЕНО: "+plate);
        captureIndicator.setTextColor(tint);
        captureIndicator.setBackgroundColor(0xDD101820);
        main.postDelayed(()->{captureIndicator.setText("● ГОТОВО ДО ФОТО");captureIndicator.setTextColor(0xFF94A3B8);},1000);
        File folder=new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),"ANPR");
        if(!folder.exists()&&!folder.mkdirs()){saving=false;return;}
        String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.ROOT).format(new Date());
        File target=new File(folder,stamp+"_"+plate+".jpg");
        Bitmap frame=preview.getBitmap();
        if(frame!=null){
            Bitmap shot=frame.copy(Bitmap.Config.ARGB_8888,true);
            Canvas shotCanvas=new Canvas(shot);
            Paint platePaint=new Paint(3);
            platePaint.setColor(tint);
            platePaint.setTextSize(Math.max(36f,shot.getWidth()/24f));
            platePaint.setTypeface(Typeface.DEFAULT_BOLD);
            platePaint.setShadowLayer(4,1,1,Color.BLACK);
            shotCanvas.drawText("● ФОТО: "+plate,24,Math.max(70,shot.getHeight()/12f),platePaint);
            snapshot.setImageBitmap(shot);
            snapshot.setVisibility(View.VISIBLE);
            main.postDelayed(()->{snapshot.setVisibility(View.GONE);snapshot.setImageDrawable(null);},1000);
        }
        imageCapture.takePicture(new ImageCapture.OutputFileOptions.Builder(target).build(),
            io,new ImageCapture.OnImageSavedCallback(){
                @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults output){
                    saving=false;
                    io.execute(()->annotatePhoto(target,plate,tint));
                    main.post(()->Toast.makeText(MainActivity.this,"Фото збережено: "+plate,Toast.LENGTH_SHORT).show());
                }
                @Override public void onError(@NonNull androidx.camera.core.ImageCaptureException e){
                    saving=false;main.post(()->status.setText("Помилка запису: "+e.getMessage()));
                }
            });
    }
    /** Overlay the recorded number on the original full-resolution image, not the low-res camera preview. */
    private void annotatePhoto(File target,String plate,int tint){
        try{
            android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();
            options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            Bitmap source=BitmapFactory.decodeFile(target.getAbsolutePath(),options);
            if(source==null)return;
            Bitmap edited=source.copy(Bitmap.Config.ARGB_8888,true);
            Canvas c=new Canvas(edited);
            float scale=Math.max(1f,edited.getWidth()/1080f);
            Paint background=new Paint(3);background.setColor(0xD9000000);
            c.drawRoundRect(new RectF(24*scale,24*scale,Math.min(edited.getWidth()-12*scale,770*scale),135*scale),12*scale,12*scale,background);
            Paint text=new Paint(3);text.setColor(tint);text.setTypeface(Typeface.DEFAULT_BOLD);text.setTextSize(48*scale);
            c.drawText("● "+plate,45*scale,94*scale,text);
            try(java.io.FileOutputStream stream=new java.io.FileOutputStream(target)){
                edited.compress(Bitmap.CompressFormat.JPEG,98,stream);
            }
            source.recycle();edited.recycle();
        }catch(Exception e){android.util.Log.w("ANPR","Photo label failed",e);}
    }
    @Override public void onDestroy(){
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
            float scale=Math.max(getWidth()/(float)sourceWidth,getHeight()/(float)sourceHeight);
            float dx=(getWidth()-sourceWidth*scale)/2f,dy=(getHeight()-sourceHeight*scale)/2f;
            RectF b=new RectF(dx+box.left*scale,dy+box.top*scale,dx+box.right*scale,dy+box.bottom*scale);
            border.setColor(shade);c.drawRoundRect(b,9,9,border);
            label.setColor(shade);c.drawText(shade==Color.GREEN?"РОЗПІЗНАНО":shade==Color.YELLOW?"ЧАСТКОВО":"НЕЧІТКО",Math.max(10,b.left),Math.max(42,b.top-12),label);
        }
    }
}
