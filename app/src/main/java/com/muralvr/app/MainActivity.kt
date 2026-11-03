package com.muralvr.app

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.hardware.Camera
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.io.File
import kotlin.math.min
import kotlin.math.hypot
import kotlin.math.atan2

class MainActivity : Activity() {
    private lateinit var host: FrameLayout
    private var vrView: VrView? = null
    private var vrActive = false
    private var eyeOffset = 0
    private var vrZoom = 100
    private var vrVertical = 0
    private lateinit var preview: DrawingView
    private lateinit var cameraView: TextureView
    private var camera: Camera? = null
    private var cameraEnabled = false
    private var cameraStatus = "apagada"
    private lateinit var info: TextView
    private var imageUri: Uri? = null
    private var image: Bitmap? = null
    private var opacity = 60
    private var widthCm = 100
    private var x = 0f
    private var y = 0f
    private var rotation = 0f
    private var locked = false
    private var stereo = false
    private var markingCorners = false
    private val corners = mutableListOf<PointF>()
    private val editableSliders = mutableListOf<SeekBar>()
    private lateinit var lockButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12,12,12,12) }
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply { text = "MuralVR 0.1 · Editor de plantillas"; textSize = 20f }
        screen.addView(title)
        info = TextView(this)
        screen.addView(info)
        preview = DrawingView()
        cameraView = TextureView(this)
        cameraView.visibility = View.VISIBLE
        preview.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        cameraView.surfaceTextureListener = object: TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) { startCamera() }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { stopCamera(); return true }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
        val imageLayer = FrameLayout(this)
        imageLayer.addView(cameraView, FrameLayout.LayoutParams(-1,-1))
        imageLayer.addView(preview, FrameLayout.LayoutParams(-1,-1))
        screen.addView(imageLayer, LinearLayout.LayoutParams(-1, 0, 1f))
        fun button(label: String, action: () -> Unit) {
            root.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("Cámara: activar / desactivar") {
            cameraEnabled = !cameraEnabled
            if(cameraEnabled) {
                if(checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), 200)
                else startCamera()
            } else stopCamera()
            preview.invalidate()
            updateInfo()
        }
        button("Importar JPG / PNG") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(intent, 100)
        }
        fun slider(label: String, max: Int, initial: Int, change: (Int) -> Unit) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val text = TextView(this).apply { this.text = label; textSize = 13f }
            text.layoutParams = LinearLayout.LayoutParams((112 * resources.displayMetrics.density).toInt(), -2)
            row.addView(text)
            row.addView(SeekBar(this).apply {
                editableSliders.add(this)
                this.max = max
                progress = initial
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) {
                        if (user) { if (locked) { Toast.makeText(this@MainActivity, "Desbloquea los ajustes primero", Toast.LENGTH_SHORT).show() } else { change(p); preview.invalidate(); updateInfo() } }
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            })
            root.addView(row)
        }
        slider("Opacidad",100,opacity) { opacity = it }
        slider("Ancho (cm)*",300,widthCm) { widthCm = it.coerceAtLeast(1) }
        slider("Giro",360,180) { rotation = (it-180).toFloat() }
        slider("Mover X",200,100) { x = (it-100).toFloat()*4f }
        slider("Mover Y",200,100) { y = (it-100).toFloat()*4f }
        slider("VR separación",200,100) { eyeOffset = it-100 }
        slider("VR tamaño",200,100) { vrZoom = it.coerceAtLeast(30) }
        slider("VR altura",200,100) { vrVertical = it-100 }
        button("CALIBRAR 4 ESQUINAS") { corners.clear(); markingCorners=true; setLocked(false); preview.invalidate(); updateInfo() }
        button("BORRAR ESQUINAS") { corners.clear(); markingCorners=false; preview.invalidate(); updateInfo() }
        button("INICIAR MODO VR (salir: Atrás)") { enterVr() }
        val actions = LinearLayout(this)
        lockButton = Button(this).apply { text="BLOQUEAR"; setOnClickListener { setLocked(!locked) } }
        actions.addView(lockButton, LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(Button(this).apply { text="Vista doble"; setOnClickListener { stereo=!stereo; preview.invalidate(); updateInfo() } }, LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(Button(this).apply { text="Guardar"; setOnClickListener { saveProject() } }, LinearLayout.LayoutParams(0,-2,1f))
        root.addView(actions)
        screen.addView(ScrollView(this).apply { addView(root) }, LinearLayout.LayoutParams(-1, 0, 1f))
        host = FrameLayout(this)
        host.addView(screen, FrameLayout.LayoutParams(-1,-1))
        setContentView(host)
        loadProject()
        setLocked(false)
        updateInfo()
    }


    private fun setLocked(value:Boolean) {
        locked=value
        if(::lockButton.isInitialized) lockButton.text=if(locked) "DESBLOQUEAR" else "BLOQUEAR"
        editableSliders.forEach { it.isEnabled=!locked }
        preview.invalidate()
        updateInfo()
        Toast.makeText(this,if(locked) "Edición bloqueada; no es anclaje a pared" else "Edición desbloqueada",Toast.LENGTH_SHORT).show()
    }

    private fun enterVr() {
        if(vrActive) return
        if(!cameraEnabled || camera == null) {
            Toast.makeText(this,"Activa la cámara antes de entrar en VR",Toast.LENGTH_LONG).show()
            return
        }
        saveProject()
        setLocked(true)
        vrActive=true
        requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.decorView.systemUiVisibility=(View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        vrView=VrView().also { host.addView(it,FrameLayout.LayoutParams(-1,-1)) }
    }

    private fun exitVr() {
        if(!vrActive) return
        vrActive=false
        vrView?.let { host.removeView(it) }
        vrView=null
        window.decorView.systemUiVisibility=0
        requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    @Deprecated("Back button")
    override fun onBackPressed() {
        if(vrActive) exitVr() else super.onBackPressed()
    }

    inner class VrView: View(this@MainActivity) {
        private val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        override fun onDraw(canvas:Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.BLACK)
            val half=width/2f
            val frame=if(cameraView.isAvailable && camera!=null) try { cameraView.getBitmap(640,360) } catch(_:Exception) { null } else null
            for(i in 0..1) {
                canvas.save()
                canvas.clipRect(i*half,0f,(i+1)*half,height.toFloat())
                val center=half*(i+0.5f)+(if(i==0) -eyeOffset else eyeOffset)*resources.displayMetrics.density
                val cy=height/2f+vrVertical*resources.displayMetrics.density
                val zoom=vrZoom/100f
                if(frame!=null) {
                    val s=maxOf(half/frame.width,height.toFloat()/frame.height)*zoom
                    val dw=frame.width*s
                    val dh=frame.height*s
                    p.alpha=255
                    canvas.drawBitmap(frame,null,RectF(center-dw/2,cy-dh/2,center+dw/2,cy+dh/2),p)
                }
                image?.let { bitmap ->
                    val base=min(half/bitmap.width,height.toFloat()/bitmap.height)
                    val scale=base*(widthCm/100f)*zoom
                    canvas.translate(center+x*half/preview.width.coerceAtLeast(1),cy+y*height/preview.height.coerceAtLeast(1))
                    canvas.rotate(rotation)
                    canvas.scale(scale,scale)
                    p.alpha=(opacity*255/100).coerceIn(0,255)
                    canvas.drawBitmap(bitmap,-bitmap.width/2f,-bitmap.height/2f,p)
                }
                canvas.restore()
            }
            frame?.recycle()
            p.alpha=255
            p.color=Color.DKGRAY
            canvas.drawRect(half-1,0f,half+1,height.toFloat(),p)
            if(vrActive) postInvalidateDelayed(100L)
        }
    }

    @Suppress("DEPRECATION")
    private fun startCamera() {
        if (!cameraEnabled) return
        if (!cameraView.isAvailable) { cameraStatus="esperando superficie"; cameraView.postDelayed({ if(cameraEnabled && camera == null) startCamera() }, 250L); updateInfo(); return }
        if (camera != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraStatus="sin permiso"; updateInfo(); return
        }
        try {
            var rearId = -1
            val details = Camera.CameraInfo()
            for (i in 0 until Camera.getNumberOfCameras()) {
                Camera.getCameraInfo(i, details)
                if (details.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { rearId=i; break }
            }
            if(rearId < 0) throw IllegalStateException("No se encuentra cámara trasera")
            val opened = Camera.open(rearId)
            camera = opened
            val infoCamera = Camera.CameraInfo()
            Camera.getCameraInfo(rearId, infoCamera)
            val degrees = when(windowManager.defaultDisplay.rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            opened.setDisplayOrientation((infoCamera.orientation-degrees+360)%360)
            opened.setPreviewTexture(cameraView.surfaceTexture)
            cameraView.visibility=View.VISIBLE
            opened.startPreview()
            cameraStatus="vista previa iniciada"
            updateInfo()
            preview.invalidate()
        } catch(e: Exception) {
            stopCamera()
            cameraStatus="error: ${e.javaClass.simpleName}: ${e.message}"
            updateInfo()
            Toast.makeText(this,"Error de cámara: ${e.message}",Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun stopCamera() {
        try { camera?.stopPreview() } catch(_:Exception) {}
        try { camera?.release() } catch(_:Exception) {}
        camera=null
        cameraStatus="apagada"
        if(::preview.isInitialized) preview.invalidate()
    }

    override fun onResume() { super.onResume(); if(::cameraView.isInitialized) startCamera() }
    override fun onPause() { stopCamera(); super.onPause() }

    override fun onRequestPermissionsResult(requestCode:Int, permissions:Array<out String>, grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==200) {
            if(grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED) startCamera()
            else { cameraEnabled=false; updateInfo(); Toast.makeText(this,"Se necesita permiso de cámara",Toast.LENGTH_LONG).show() }
        }
    }

    private fun updateInfo() {
        info.text = "Opacidad: $opacity% · Ancho: $widthCm cm* · Giro: ${rotation.toInt()}° · X: ${x.toInt()} · Y: ${y.toInt()} · " +
            (if(locked) "EDICIÓN BLOQUEADA (NO ANCLADO)" else "Editable") +
            (if(stereo) " · Vista doble experimental" else "") +
            "\\nCámara: $cameraStatus" +
            "\n*Medida orientativa, sin calibración espacial."
    }

    @Deprecated("Legacy activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==100 && resultCode==RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    imageUri=uri
                    loadImage(uri)
                    preview.invalidate()
                    Toast.makeText(this,"Imagen cargada",Toast.LENGTH_SHORT).show()
                } catch(e: Exception) { Toast.makeText(this,"Error al abrir imagen: ${e.message}",Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun loadImage(uri: Uri) {
        contentResolver.openInputStream(uri)?.use { stream ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream,null,options)
            val factor = (maxOf(options.outWidth,options.outHeight)/1600).coerceAtLeast(1)
            contentResolver.openInputStream(uri)?.use { input ->
                image = BitmapFactory.decodeStream(input,null,BitmapFactory.Options().apply { inSampleSize=factor })
            }
        }
    }

    private fun saveProject() {
        try {
            val json = JSONObject().apply {
                put("uri",imageUri?.toString() ?: "")
                put("opacity",opacity); put("widthCm",widthCm)
                put("x",x.toDouble()); put("y",y.toDouble())
                put("rotation",rotation.toDouble()); put("locked",locked); put("stereo",stereo)
                put("eyeOffset",eyeOffset); put("vrZoom",vrZoom); put("vrVertical",vrVertical)
            }
            File(filesDir,"project.json").writeText(json.toString())
            Toast.makeText(this,"Proyecto guardado",Toast.LENGTH_SHORT).show()
        } catch(e:Exception) { Toast.makeText(this,"No se pudo guardar",Toast.LENGTH_SHORT).show() }
    }
    private fun loadProject() {
        try {
            val f=File(filesDir,"project.json")
            if(!f.exists()) return
            val j=JSONObject(f.readText())
            opacity=j.optInt("opacity",60); widthCm=j.optInt("widthCm",100)
            x=j.optDouble("x",0.0).toFloat(); y=j.optDouble("y",0.0).toFloat()
            rotation=j.optDouble("rotation",0.0).toFloat()
            locked=false; stereo=j.optBoolean("stereo")
            eyeOffset=j.optInt("eyeOffset",0); vrZoom=j.optInt("vrZoom",100); vrVertical=j.optInt("vrVertical",0)
            val uri=j.optString("uri")
            if(uri.isNotEmpty()) { imageUri=Uri.parse(uri); loadImage(imageUri!!) }
            preview.invalidate()
        } catch(_:Exception) {}
    }
    inner class DrawingView(context: android.content.Context = this@MainActivity): View(context) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private var lastCx=0f
        private var lastCy=0f
        private var lastDistance=0f
        private var lastAngle=0f
        private var previousCount=0

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (image == null || locked) return true
            val n=event.pointerCount
            if (n < 1) return true
            val cx=if(n>=2) (event.getX(0)+event.getX(1))/2f else event.getX(0)
            val cy=if(n>=2) (event.getY(0)+event.getY(1))/2f else event.getY(0)
            val dx=if(n>=2) event.getX(1)-event.getX(0) else 0f
            val dy=if(n>=2) event.getY(1)-event.getY(0) else 0f
            val distance=hypot(dx,dy)
            val angle=Math.toDegrees(atan2(dy.toDouble(),dx.toDouble())).toFloat()
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP -> previousCount=0
                MotionEvent.ACTION_MOVE -> {
                    if(previousCount==n) {
                        x+=cx-lastCx
                        y+=cy-lastCy
                        if(n>=2 && lastDistance>5f && distance>5f) {
                            widthCm=(widthCm*(distance/lastDistance)).toInt().coerceIn(5,1000)
                            var delta=angle-lastAngle
                            if(delta>180f) delta-=360f
                            if(delta< -180f) delta+=360f
                            rotation+=delta
                        }
                        invalidate()
                        updateInfo()
                    }
                    previousCount=n
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> previousCount=0
            }
            lastCx=cx
            lastCy=cy
            lastDistance=distance
            lastAngle=angle
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(if(cameraEnabled && camera != null) Color.TRANSPARENT else Color.rgb(235,235,235), PorterDuff.Mode.CLEAR)
            val bitmap=image ?: run {
                paint.color=Color.DKGRAY; paint.textSize=34f
                canvas.drawText("Importa una imagen",25f,height/2f,paint)
                return
            }
            val count=if(stereo) 2 else 1
            for(i in 0 until count) {
                val w=width.toFloat()/count
                val centerX=w*(i+0.5f)
                val centerY=height/2f
                val base=min(w/bitmap.width.toFloat(),height/bitmap.height.toFloat())
                val scale=base*(widthCm/100f)
                canvas.save()
                canvas.clipRect(i*w,0f,(i+1)*w,height.toFloat())
                canvas.translate(centerX+x,centerY+y)
                canvas.rotate(rotation)
                canvas.scale(scale,scale)
                paint.alpha=(opacity*255/100).coerceIn(0,255)
                canvas.drawBitmap(bitmap,-bitmap.width/2f,-bitmap.height/2f,paint)
                canvas.restore()
            }
            paint.alpha=255
            if(stereo) {
                paint.color=Color.GRAY; paint.strokeWidth=2f
                canvas.drawLine(width/2f,0f,width/2f,height.toFloat(),paint)
            }
        }
    }
}