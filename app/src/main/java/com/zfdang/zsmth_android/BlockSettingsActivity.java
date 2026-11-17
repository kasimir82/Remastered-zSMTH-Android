package com.zfdang.zsmth_android;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.zfdang.zsmth_android.helpers.BlockListManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 屏蔽设置: 管理屏蔽用户与屏蔽标题关键词, 支持 JSON 导入导出到 Download 目录
 */
public class BlockSettingsActivity extends SMTHBaseActivity {

    private static final String TAG = "BlockSettings";
    private static final int REQ_PICK_JSON = 1001;
    private static final int REQ_WRITE_STORAGE = 1002;
    private static final String EXPORT_FILE_PREFIX = "zsmth_blocklist_";

    private RecyclerView mRecyclerView;
    private TextView mEmptyHint;
    private BlockEntryAdapter mAdapter;

    private final List<BlockListManager.Entry> mEntries = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_block_settings);
        setupBottomNavigation();

        setSupportActionBar(findViewById(R.id.toolbar));
        setTitle("屏蔽设置");
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        mRecyclerView = findViewById(R.id.block_list);
        mEmptyHint = findViewById(R.id.empty_hint);

        mAdapter = new BlockEntryAdapter();
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mRecyclerView.setAdapter(mAdapter);

        FloatingActionButton fab = findViewById(R.id.fab_add);
        fab.setOnClickListener(view -> showAddDialog());

        reload();
    }

    private void reload() {
        mEntries.clear();
        mEntries.addAll(BlockListManager.getInstance().getEntries());
        mAdapter.notifyDataSetChanged();
        mEmptyHint.setVisibility(mEntries.isEmpty() ? View.VISIBLE : View.GONE);
        mRecyclerView.setVisibility(mEntries.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showAddDialog() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_add_block, null, false);
        RadioGroup typeGroup = content.findViewById(R.id.type_group);
        EditText input = content.findViewById(R.id.value_input);

        new AlertDialog.Builder(this)
                .setTitle("添加屏蔽规则")
                .setView(content)
                .setPositiveButton("添加", (dialog, which) -> {
                    String value = input.getText().toString();
                    if (TextUtils.isEmpty(value.trim())) {
                        Toast.makeText(this, "内容不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int type = typeGroup.getCheckedRadioButtonId() == R.id.type_user
                            ? BlockListManager.TYPE_USER
                            : BlockListManager.TYPE_KEYWORD;
                    boolean added = BlockListManager.getInstance().add(type, value);
                    Toast.makeText(this, added ? "已添加" : "该规则已存在", Toast.LENGTH_SHORT).show();
                    reload();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDelete(final BlockListManager.Entry entry) {
        new AlertDialog.Builder(this)
                .setMessage("删除这条屏蔽规则？\n\n" + (entry.isUser() ? "用户：" : "关键词：") + entry.value)
                .setPositiveButton("删除", (dialog, which) -> {
                    BlockListManager.getInstance().remove(entry.type, entry.value);
                    reload();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.block_settings_menu, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.action_import) {
            pickJsonFile();
            return true;
        } else if (id == R.id.action_export) {
            exportToDownload();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ---------------------------------------------------------------- 导出

    private void exportToDownload() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE_STORAGE);
            return;
        }
        doExport();
    }

    private void doExport() {
        String json = BlockListManager.getInstance().exportJson();
        String fileName = EXPORT_FILE_PREFIX
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".json";

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    Toast.makeText(this, "导出失败：无法创建文件", Toast.LENGTH_LONG).show();
                    return;
                }
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new IllegalStateException("openOutputStream returned null");
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                Toast.makeText(this, "已导出到 Download/" + fileName, Toast.LENGTH_LONG).show();
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                }
                File file = new File(dir, fileName);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(json.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
                MediaScannerConnection.scanFile(this, new String[] {file.getAbsolutePath()},
                        new String[] {"application/json"}, null);
                Toast.makeText(this, "已导出到 " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "export failed", e);
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                                   @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WRITE_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                doExport();
            } else {
                Toast.makeText(this, "需要存储权限才能导出到 Download", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------------------------------------------------------------- 导入

    private void pickJsonFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[] {"application/json", "text/plain", "application/octet-stream"});
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 默认打开 Download 目录
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"));
        }
        try {
            startActivityForResult(intent, REQ_PICK_JSON);
        } catch (Exception e) {
            Log.e(TAG, "no document picker", e);
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_JSON || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) return;

        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) throw new IllegalStateException("无法读取文件");
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            int[] result = BlockListManager.getInstance().importJson(sb.toString());
            if (result[0] < 0) {
                Toast.makeText(this, "导入失败：文件不是合法的 JSON", Toast.LENGTH_LONG).show();
                return;
            }
            reload();
            Toast.makeText(this, String.format(Locale.US, "导入完成：新增 %d 个用户、%d 个关键词",
                    result[0], result[1]), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "import failed", e);
            Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- 列表

    private class BlockEntryAdapter extends RecyclerView.Adapter<BlockEntryAdapter.ViewHolder> {

        class ViewHolder extends RecyclerView.ViewHolder {
            final TextView typeLabel;
            final TextView valueText;
            final TextView deleteButton;

            ViewHolder(View view) {
                super(view);
                typeLabel = view.findViewById(R.id.type_label);
                valueText = view.findViewById(R.id.value_text);
                deleteButton = view.findViewById(R.id.delete_button);
            }
        }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_block_entry, parent, false);
            return new ViewHolder(view);
        }

        @Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final BlockListManager.Entry entry = mEntries.get(position);
            holder.typeLabel.setText(entry.isUser() ? "用户" : "关键词");
            holder.valueText.setText(entry.value);
            holder.deleteButton.setOnClickListener(view -> confirmDelete(entry));
        }

        @Override public int getItemCount() {
            return mEntries.size();
        }
    }
}
