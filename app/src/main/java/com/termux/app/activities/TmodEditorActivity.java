package com.termux.app.activities;

import android.os.Bundle;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Edits text files under the Tmod home directory. */
public final class TmodEditorActivity extends AppCompatActivity {

    private static final int MAX_BYTES = 1024 * 1024;

    private FrameLayout mContainer;
    private File mDirectory;
    private File mOpenFile;
    private EditText mEditor;
    private boolean mDirty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContainer = new FrameLayout(this);
        setContentView(mContainer);
        File home = TermuxConstants.TERMUX_HOME_DIR;
        if (!home.isDirectory() && !home.mkdirs()) {
            Toast.makeText(this, home.getAbsolutePath(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        mDirectory = home;
        showDirectory();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.tmod_editor_new);
        menu.add(0, 2, 1, R.string.tmod_editor_save);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) {
            askNewFile();
            return true;
        }
        if (item.getItemId() == 2) {
            saveOpenFile();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (mOpenFile != null) {
            showDirectory();
            return;
        }
        File home = TermuxConstants.TERMUX_HOME_DIR;
        if (mDirectory != null && !sameFile(mDirectory, home) && mDirectory.getParentFile() != null) {
            mDirectory = mDirectory.getParentFile();
            showDirectory();
            return;
        }
        super.onBackPressed();
    }

    private void showDirectory() {
        mOpenFile = null;
        mEditor = null;
        mDirty = false;
        setTitle(mDirectory.getAbsolutePath());
        ListView list = new ListView(this);
        File[] children = mDirectory.listFiles();
        if (children == null) children = new File[0];
        Arrays.sort(children, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        List<String> labels = new ArrayList<>();
        List<File> files = new ArrayList<>();
        for (File child : children) {
            labels.add(child.isDirectory() ? child.getName() + "/" : child.getName());
            files.add(child);
        }
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        list.setOnItemClickListener((parent, view, position, id) -> openEntry(files.get(position)));
        mContainer.removeAllViews();
        mContainer.addView(list);
    }

    private void openEntry(File file) {
        if (file.isDirectory()) {
            mDirectory = file;
            showDirectory();
            return;
        }
        byte[] data;
        try {
            data = readLimited(file);
        } catch (IOException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        for (byte value : data) {
            if (value == 0) {
                Toast.makeText(this, R.string.tmod_editor_binary, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        mOpenFile = file;
        mEditor = new EditText(this);
        mEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        mEditor.setHorizontallyScrolling(true);
        mEditor.setText(new String(data, StandardCharsets.UTF_8));
        mEditor.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { mDirty = true; }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        mDirty = false;
        setTitle(file.getName());
        mContainer.removeAllViews();
        mContainer.addView(mEditor);
    }

    private void askNewFile() {
        EditText name = new EditText(this);
        name.setHint(R.string.tmod_editor_name);
        new AlertDialog.Builder(this)
            .setTitle(R.string.tmod_editor_new)
            .setView(name)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String value = name.getText().toString().trim();
                if (value.isEmpty() || value.contains("/") || value.contains("\\") || value.equals(".") || value.equals("..")) {
                    return;
                }
                File created = new File(mDirectory, value);
                try {
                    if (!created.exists() && !created.createNewFile()) {
                        throw new IOException(created.getAbsolutePath());
                    }
                } catch (IOException e) {
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    return;
                }
                openEntry(created);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void saveOpenFile() {
        if (mOpenFile == null || mEditor == null) return;
        try (FileOutputStream output = new FileOutputStream(mOpenFile)) {
            output.write(mEditor.getText().toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        mDirty = false;
        Toast.makeText(this, R.string.tmod_editor_saved, Toast.LENGTH_SHORT).show();
    }

    private static byte[] readLimited(File file) throws IOException {
        if (file.length() > MAX_BYTES) throw new IOException("file is larger than 1 MiB");
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_BYTES) throw new IOException("file is larger than 1 MiB");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static boolean sameFile(File left, File right) {
        return left.getAbsolutePath().equals(right.getAbsolutePath());
    }

}
