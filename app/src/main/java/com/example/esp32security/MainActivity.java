package com.example.esp32security;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private static final String CHANNEL_ID = "security_alerts_channel";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    
    private TextView tvStatus;
    private Button btnConnect;
    private LinearLayout feedContainer;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private InputStream inputStream;
    private boolean isConnected = false;
    private Thread workerThread;

    private TextToSpeech tts;
    private Vibrator vibrator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        btnConnect = findViewById(R.id.btnConnect);
        feedContainer = findViewById(R.id.feedContainer);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        tts = new TextToSpeech(this, this);

        createNotificationChannel();
        requestPermissions();

        btnConnect.setOnClickListener(v -> {
            if (!isConnected) {
                findAndConnectESP32();
            } else {
                disconnectBT();
            }
        });
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.US);
        }
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.VIBRATE
            }, 101);
        }
    }

    private void findAndConnectESP32() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return;
        }

        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        BluetoothDevice espDevice = null;

        if (pairedDevices != null) {
            for (BluetoothDevice device : pairedDevices) {
                if ("ESP32_DoorSecurity".equals(device.getName()) || "ESP32_TestDevice".equals(device.getName())) {
                    espDevice = device;
                    break;
                }
            }
        }

        if (espDevice != null) {
            connectToDevice(espDevice);
        } else {
            tvStatus.setText("System: ESP32 Not Paired");
        }
    }

    private void connectToDevice(BluetoothDevice device) {
        new Thread(() -> {
            try {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    return;
                }
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothSocket.connect();
                inputStream = bluetoothSocket.getInputStream();

                isConnected = true;
                updateStatusUI("System: ACTIVE & LISTENING", Color.GREEN);
                startDataListener();

            } catch (IOException e) {
                isConnected = false;
                updateStatusUI("System: Connection Failed", Color.RED);
            }
        }).start();
    }

    private void startDataListener() {
        workerThread = new Thread(() -> {
            byte[] buffer = new byte[1024];
            ByteArrayOutputStream imageBuffer = new ByteArrayOutputStream();
            boolean receivingImage = false;
            int imageSize = 0;

            while (!Thread.currentThread().isInterrupted() && isConnected) {
                try {
                    int available = inputStream.available();
                    if (available > 0) {
                        if (!receivingImage) {
                            int bytesRead = inputStream.read(buffer);
                            String incoming = new String(buffer, 0, bytesRead).trim();

                            if (incoming.contains("ALERT:HUMAN")) {
                                receivingImage = true;
                                imageSize = 0;
                                imageBuffer.reset();
                                triggerAlertActions();
                            }
                        } else {
                            if (imageSize == 0) {
                                if (available >= 4) {
                                    byte[] sizeBytes = new byte[4];
                                    inputStream.read(sizeBytes);
                                    imageSize = (sizeBytes[0] & 0xFF) |
                                                ((sizeBytes[1] & 0xFF) << 8) |
                                                ((sizeBytes[2] & 0xFF) << 16) |
                                                ((sizeBytes[3] & 0xFF) << 24);
                                }
                            } else {
                                int bytesToRead = Math.min(available, imageSize - imageBuffer.size());
                                if (bytesToRead > 0) {
                                    byte[] dataChunk = new byte[bytesToRead];
                                    inputStream.read(dataChunk);
                                    imageBuffer.write(dataChunk);
                                }

                                if (imageBuffer.size() >= imageSize) {
                                    receivingImage = false;
                                    final byte[] finalBytes = imageBuffer.toByteArray();
                                    new Handler(Looper.getMainLooper()).post(() -> processIncomingSnapshot(finalBytes));
                                }
                            }
                        }
                    } else {
                        Thread.sleep(10);
                    }
                } catch (Exception e) {
                    isConnected = false;
                    updateStatusUI("System: Disconnected", Color.RED);
                    break;
                }
            }
        });
        workerThread.start();
    }

    private void triggerAlertActions() {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (tts != null) {
                tts.speak("Someone is on the door, please check", TextToSpeech.QUEUE_FLUSH, null, null);
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(1000);
                }
            }
        });
    }

    private void processIncomingSnapshot(byte[] jpegBytes) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        if (bitmap == null) return;

        saveImageToGallery(bitmap);
        sendSystemNotification(bitmap);
        addMessageToFeed(bitmap);
    }

    private void addMessageToFeed(Bitmap bitmap) {
        String timestamp = new SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(new Date());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.parseColor("#1E1E1E"));
        card.setPadding(32, 32, 32, 32);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, 24);
        card.setLayoutParams(params);

        TextView timeTv = new TextView(this);
        timeTv.setText("🚨 DOORBELL ALERT • " + timestamp);
        timeTv.setTextColor(Color.parseColor("#FF5252"));
        timeTv.setTextSize(14);
        timeTv.setTypeface(null, android.graphics.Typeface.BOLD);

        ImageView imgView = new ImageView(this);
        imgView.setImageBitmap(bitmap);
        imgView.setAdjustViewBounds(true);
        imgView.setPadding(0, 16, 0, 16);

        TextView msgTv = new TextView(this);
        msgTv.setText("Person detected at the front door. Image archived to Gallery.");
        msgTv.setTextColor(Color.parseColor("#CCCCCC"));

        card.addView(timeTv);
        card.addView(imgView);
        card.addView(msgTv);

        feedContainer.addView(card, 0);
    }

    private void sendSystemNotification(Bitmap bitmap) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("🚨 Doorbell Alert!")
                .setContentText("Someone is on the door, please check!")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setStyle(new NotificationCompat.BigPictureStyle().bigPicture(bitmap))
                .setAutoCancel(true);

        notificationManager.notify((int) System.currentTimeMillis(), builder.build());
    }

    private void saveImageToGallery(Bitmap bitmap) {
        String filename = "Doorbell_" + System.currentTimeMillis() + ".jpg";
        OutputStream fos = null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/DoorbellSnapshots");

            Uri imageUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);
            try {
                if (imageUri != null) fos = getContentResolver().openOutputStream(imageUri);
            } catch (IOException e) { e.printStackTrace(); }
        }

        if (fos != null) {
            try {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                fos.close();
                Toast.makeText(this, "Saved to Gallery!", Toast.LENGTH_SHORT).show();
            } catch (IOException e) { e.printStackTrace(); }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Security Alerts", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Shows live image alerts from ESP32 security camera");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void updateStatusUI(final String status, final int color) {
        new Handler(Looper.getMainLooper()).post(() -> {
            tvStatus.setText(status);
            tvStatus.setTextColor(color);
            btnConnect.setText(isConnected ? "Disconnect" : "Connect");
        });
    }

    private void disconnectBT() {
        isConnected = false;
        if (workerThread != null) workerThread.interrupt();
        try {
            if (inputStream != null) inputStream.close();
            if (bluetoothSocket != null) bluetoothSocket.close();
        } catch (IOException ignored) {}
        updateStatusUI("System: Offline", Color.RED);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}