package com.soundbridge.app.ui;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.soundbridge.app.databinding.ActivityApiDemoBinding;
import com.soundbridge.app.util.HttpDemoClient;

import java.util.Locale;

/**
 * Service Connectivity Diagnostics screen (isolated HTTPS JSON & XML checks).
 */
public class ApiDemoActivity extends AppCompatActivity {

    private ActivityApiDemoBinding binding;
    private HttpDemoClient httpDemoClient;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityApiDemoBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        httpDemoClient = new HttpDemoClient();

        binding.btnBackApiDemo.setOnClickListener(v -> finish());

        binding.btnFetchJson.setOnClickListener(v -> {
            CharSequence text = binding.etJsonUrl.getText();
            runNetworkDemo(text != null ? text.toString().trim() : "", true);
        });

        binding.btnFetchXml.setOnClickListener(v -> {
            CharSequence text = binding.etXmlUrl.getText();
            runNetworkDemo(text != null ? text.toString().trim() : "", false);
        });

        binding.btnRunLocalParseDemo.setOnClickListener(v -> runOfflineParserSelfTest());
    }

    private void runNetworkDemo(String url, final boolean isJson) {
        binding.tvApiStatusHeader.setText("Checking connection...");
        binding.btnFetchJson.setEnabled(false);
        binding.btnFetchXml.setEnabled(false);

        HttpDemoClient.ApiResultCallback callback = new HttpDemoClient.ApiResultCallback() {
            @Override
            public void onSuccess(int httpStatus, String formattedSummary, String rawBodyPreview) {
                binding.btnFetchJson.setEnabled(true);
                binding.btnFetchXml.setEnabled(true);
                binding.tvApiStatusHeader.setText(String.format(Locale.US,
                        "Connected (HTTP %d)", httpStatus));
                binding.tvApiParsedOutput.setText(formattedSummary);
                binding.tvApiRawPreview.setText(rawBodyPreview);
            }

            @Override
            public void onError(String friendlyMessage) {
                binding.btnFetchJson.setEnabled(true);
                binding.btnFetchXml.setEnabled(true);
                binding.tvApiStatusHeader.setText("Offline — On-device sound detection unaffected");
                binding.tvApiParsedOutput.setText(friendlyMessage);
                binding.tvApiRawPreview.setText("Tap 'Run Offline Diagnostics' above to verify data parsers offline.");
            }
        };

        if (isJson) {
            httpDemoClient.runJsonGetDemo(url, callback);
        } else {
            httpDemoClient.runXmlGetDemo(url, callback);
        }
    }

    private void runOfflineParserSelfTest() {
        String sampleJson = "{\"service\":\"SoundBridge\",\"sample_rate_hz\":16000,\"categories\":[\"Doorbell\",\"Knock\",\"Alarm\",\"Appliance Chime\"]}";
        String sampleXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><soundbridge><catalog version=\"1.0\"><category id=\"doorbell\">Doorbell</category><category id=\"alarm\">Smoke &amp; Safety Alarm</category></catalog></soundbridge>";

        try {
            String parsedJson = HttpDemoClient.parseJsonSummary(sampleJson);
            String parsedXml = HttpDemoClient.parseXmlWithPullParser(sampleXml);

            binding.tvApiStatusHeader.setText("Offline Diagnostics Passed");
            binding.tvApiParsedOutput.setText("JSON Check:\n" + parsedJson
                    + "\nXML Check:\n" + parsedXml);
            binding.tvApiRawPreview.setText("Sample JSON:\n" + sampleJson + "\n\nSample XML:\n" + sampleXml);
        } catch (Exception e) {
            binding.tvApiStatusHeader.setText("Diagnostics Error");
            binding.tvApiParsedOutput.setText(e.getMessage());
        }
    }
}
