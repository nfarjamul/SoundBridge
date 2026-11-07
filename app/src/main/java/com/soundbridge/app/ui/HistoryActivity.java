package com.soundbridge.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.soundbridge.app.data.SoundBridgeRepository;
import com.soundbridge.app.databinding.ActivityHistoryBinding;
import com.soundbridge.app.model.AlertEvent;

/**
 * Sound-alert history screen with category filter chips, clean timeline cards,
 * individual deletion, and Clear All confirmation.
 */
public class HistoryActivity extends AppCompatActivity implements AlertHistoryAdapter.OnAlertClickListener {

    private ActivityHistoryBinding binding;
    private SoundBridgeRepository repository;
    private AlertHistoryAdapter adapter;
    private String currentFilter = "ALL";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityHistoryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = SoundBridgeRepository.getInstance(this);
        adapter = new AlertHistoryAdapter(this);

        binding.rvAlertHistory.setLayoutManager(new LinearLayoutManager(this));
        binding.rvAlertHistory.setAdapter(adapter);

        binding.btnBackHistory.setOnClickListener(v -> finish());
        binding.btnFilterAll.setOnClickListener(v -> applyFilter("ALL"));
        binding.btnFilterBuiltIn.setOnClickListener(v -> applyFilter(AlertEvent.SOURCE_BUILT_IN));
        binding.btnFilterCustom.setOnClickListener(v -> applyFilter(AlertEvent.SOURCE_CUSTOM));
        binding.btnClearHistory.setOnClickListener(v -> confirmClearAll());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadHistoryItems();
    }

    private void applyFilter(String filter) {
        this.currentFilter = filter;
        loadHistoryItems();
    }

    private void loadHistoryItems() {
        repository.loadAlertsAsync(currentFilter, events -> {
            adapter.submitList(events);
            int size = events != null ? events.size() : 0;
            binding.btnClearHistory.setVisibility(size > 0 ? View.VISIBLE : View.INVISIBLE);

            if (size == 0) {
                binding.tvEmptyHistoryState.setVisibility(View.VISIBLE);
                binding.rvAlertHistory.setVisibility(View.GONE);
            } else {
                binding.tvEmptyHistoryState.setVisibility(View.GONE);
                binding.rvAlertHistory.setVisibility(View.VISIBLE);
            }
        });
    }

    private void confirmClearAll() {
        new AlertDialog.Builder(this)
                .setTitle("Clear Alert History?")
                .setMessage("This will remove all past sound alerts from your history.")
                .setPositiveButton("Clear All", (dialog, which) ->
                        repository.clearAllAlertsAsync(() -> {
                            loadHistoryItems();
                            Toast.makeText(HistoryActivity.this, "History cleared", Toast.LENGTH_SHORT).show();
                        }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onAlertClick(AlertEvent alert) {
        Intent intent = new Intent(this, AlertDetailActivity.class);
        intent.putExtra(AlertDetailActivity.EXTRA_ALERT_ID, alert.getId());
        startActivity(intent);
    }

    @Override
    public void onAlertDeleteClick(AlertEvent alert) {
        repository.deleteAlertAsync(alert.getId(), () -> {
            loadHistoryItems();
            Toast.makeText(HistoryActivity.this, "Alert removed", Toast.LENGTH_SHORT).show();
        });
    }
}
