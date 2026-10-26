package com.ignotek.solarscope;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;
import com.ignotek.solarscope.callscope.CallScopePlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(CallScopePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
