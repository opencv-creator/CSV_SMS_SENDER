package com.example.csvsmssender;

import android.Manifest;
import android.app.Activity;
import androidx.appcompat.app.AppCompatActivity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_SMS = 100;
    private static final int REQ_CSV = 101;
    private static final int REQ_NOTIF = 102;

    Button selectButton, sendButton, stopButton;
    EditText delayEdit;
    TextView fileName, countText, statusText, logText;
    ProgressBar progress;
    ScrollView logScroll;
    ArrayList<SmsRow> rows = new ArrayList<>();
    volatile boolean stopped = false;
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Handler main = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (SmsService.ACTION_UPDATE.equals(intent.getAction())) {
                int sent = intent.getIntExtra(SmsService.EXTRA_SENT, 0);
                int failed = intent.getIntExtra(SmsService.EXTRA_FAILED, 0);
                int p = intent.getIntExtra(SmsService.EXTRA_PROGRESS, 0);
                int total = intent.getIntExtra(SmsService.EXTRA_TOTAL, 0);
                String status = intent.getStringExtra(SmsService.EXTRA_STATUS);
                String log = intent.getStringExtra(SmsService.EXTRA_LOG);
                boolean finished = intent.getBooleanExtra(SmsService.EXTRA_FINISHED, false);

                progress.setProgress(p);
                statusText.setText(status);
                if (log != null) {
                    logText.append(log);
                    logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
                }

                if (finished) {
                    stopButton.setEnabled(false);
                    selectButton.setEnabled(true);
                    sendButton.setEnabled(!rows.isEmpty());
                    String finalStatus = (p < total) ? "Stopped. Sent: " + sent + ", Failed: " + failed :
                            "Finished. Sent: " + sent + ", Failed: " + failed;
                    statusText.setText(finalStatus);
                }
            }
        }
    };

    static class SmsRow {
        String number, message, fullRow;
        SmsRow(String n, String m, String f) { number=n; message=m; fullRow=f; }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        selectButton=findViewById(R.id.selectButton);
        sendButton=findViewById(R.id.sendButton);
        stopButton=findViewById(R.id.stopButton);
        delayEdit=findViewById(R.id.delayEdit);
        fileName=findViewById(R.id.fileName);
        countText=findViewById(R.id.countText);
        statusText=findViewById(R.id.statusText);
        logText=findViewById(R.id.logText);
        progress=findViewById(R.id.progress);
        logScroll=findViewById(R.id.logScroll);

        selectButton.setOnClickListener(v -> chooseCsv());
        sendButton.setOnClickListener(v -> startSending());
        stopButton.setOnClickListener(v -> {
            stopped = true;
            Intent intent = new Intent(this, SmsService.class);
            intent.setAction(SmsService.ACTION_STOP);
            startService(intent);
        });

        IntentFilter filter = new IntentFilter(SmsService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateReceiver, filter);
        }

        restoreState();
    }

    private void restoreState() {
        if (SmsService.isRunning) {
            progress.setMax(SmsService.currentTotal);
            progress.setProgress(SmsService.currentProgress);
            statusText.setText(SmsService.currentStatus);
            logText.setText(SmsService.logHistory.toString());
            sendButton.setEnabled(false);
            selectButton.setEnabled(false);
            stopButton.setEnabled(true);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        restoreState();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        restoreState();
    }

    private void chooseCsv() {
        IntentHelper.openCsv(this, REQ_CSV);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQ_CSV && resultCode==RESULT_OK && data!=null && data.getData()!=null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch(Exception ignored) {}
            loadCsv(uri);
        }
    }

    private void loadCsv(Uri uri) {
        executor.execute(() -> {
            ArrayList<SmsRow> parsed = new ArrayList<>();
            int bad = 0;
            try (InputStream in = getContentResolver().openInputStream(uri);
                 BufferedReader br = new BufferedReader(
                         new InputStreamReader(in, StandardCharsets.UTF_8))) {

                // Read the complete CSV as UTF-8. This preserves Telugu and other Unicode text.
                StringBuilder all = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    all.append(line).append('\n');
                }

                List<List<String>> records = parseCsvRecords(all.toString());

                boolean first = true;
                for (List<String> cols : records) {
                    if (cols.isEmpty()) continue;

                    // Remove UTF-8 BOM if present.
                    if (!cols.get(0).isEmpty() &&
                            cols.get(0).charAt(0) == '\uFEFF') {
                        cols.set(0, cols.get(0).substring(1));
                    }

                    // Optional header check: roll, phone, message
                    if (first && cols.size() >= 3 &&
                            (cols.get(0).trim().toLowerCase(Locale.ROOT).contains("roll") ||
                             cols.get(1).trim().toLowerCase(Locale.ROOT).contains("phone") ||
                             cols.get(1).trim().toLowerCase(Locale.ROOT).contains("number"))) {
                        first = false;
                        continue;
                    }
                    first = false;

                    if (cols.size() < 3) {
                        bad++;
                        continue;
                    }

                    String number = cols.get(1).trim();
                    String message = cols.get(2); // Keep Telugu punctuation/quotes exactly.

                    if (!TextUtils.isEmpty(number) && !TextUtils.isEmpty(message.trim())) {
                        String fullRow = TextUtils.join(" | ", cols);
                        parsed.add(new SmsRow(number, message, fullRow));
                    } else {
                        bad++;
                    }
                }

                String name = getFileName(uri);
                final int finalBad = bad;
                main.post(() -> {
                    rows = parsed;
                    fileName.setText(name);
                    countText.setText(String.format(Locale.ROOT, "Rows: %d   Invalid/skipped: %d", rows.size(), finalBad));
                    sendButton.setEnabled(!rows.isEmpty());
                    logText.setText("");
                    statusText.setText(rows.isEmpty()
                            ? "No valid rows found."
                            : "CSV loaded. Telugu/Unicode text is supported.");
                });
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this,
                        "CSV error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    // RFC-4180-style CSV parser:
    // - comma separates columns
    // - commas inside "quoted text" are preserved
    // - Telugu/Unicode is preserved because the file is read as UTF-8
    // - double quotes inside quoted fields are represented by ""
    // - newline inside a quoted message is supported
    private static List<List<String>> parseCsvRecords(String csv) {
        ArrayList<List<String>> records = new ArrayList<>();
        ArrayList<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < csv.length(); i++) {
            char ch = csv.charAt(i);

            if (ch == '"') {
                if (inQuotes && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                row.add(field.toString());
                field.setLength(0);
            } else if ((ch == '\n' || ch == '\r') && !inQuotes) {
                if (ch == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);

                boolean hasData = false;
                for (String x : row) {
                    if (!x.trim().isEmpty()) { hasData = true; break; }
                }
                if (hasData) records.add(row);
                row = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }

        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            boolean hasData = false;
            for (String x : row) {
                if (!x.trim().isEmpty()) { hasData = true; break; }
            }
            if (hasData) records.add(row);
        }
        return records;
    }

    private void startSending() {
        if(rows.isEmpty()) return;
        if(checkSelfPermission(Manifest.permission.SEND_SMS)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.SEND_SMS}, REQ_SMS);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                return;
            }
        }

        int delay;
        try { delay=Math.max(0,Integer.parseInt(delayEdit.getText().toString().trim())); }
        catch(Exception e) { delay=15; }

        stopped=false;
        sendButton.setEnabled(false); selectButton.setEnabled(false); stopButton.setEnabled(true);
        progress.setMax(rows.size()); progress.setProgress(0);
        logText.setText("");

        ArrayList<String> numbers = new ArrayList<>();
        ArrayList<String> messages = new ArrayList<>();
        ArrayList<String> fullRows = new ArrayList<>();
        for (SmsRow r : rows) {
            numbers.add(r.number);
            messages.add(r.message);
            fullRows.add(r.fullRow);
        }

        Intent serviceIntent = new Intent(this, SmsService.class);
        serviceIntent.putStringArrayListExtra("numbers", numbers);
        serviceIntent.putStringArrayListExtra("messages", messages);
        serviceIntent.putStringArrayListExtra("fullRows", fullRows);
        serviceIntent.putExtra("delay", delay);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private String getFileName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Error getting file name", e);
        }
        return uri.toString();
    }

    @Override protected void onDestroy() {
        try {
            unregisterReceiver(updateReceiver);
        } catch (Exception ignored) {}
        stopped=true; executor.shutdownNow(); super.onDestroy();
    }

    static class IntentHelper {
        static void openCsv(Activity a,int code) {
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/csv","text/comma-separated-values","text/plain"});
            a.startActivityForResult(i,code);
        }
    }
}
