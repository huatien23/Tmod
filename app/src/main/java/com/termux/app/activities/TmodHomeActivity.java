package com.termux.app.activities;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.TermuxActivity;

/** First screen. Terminal and the file editor are separate from here. */
public final class TmodHomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tmod_home);
        findViewById(R.id.tmod_open_terminal).setOnClickListener(v ->
            startActivity(new Intent(this, TermuxActivity.class)));
        findViewById(R.id.tmod_open_editor).setOnClickListener(v ->
            startActivity(new Intent(this, TmodEditorActivity.class)));
    }

}
