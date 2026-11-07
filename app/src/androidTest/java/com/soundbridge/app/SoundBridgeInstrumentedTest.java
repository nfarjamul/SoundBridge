package com.soundbridge.app;

import android.content.Context;
import android.database.Cursor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.soundbridge.app.data.AlertDatabaseHelper;
import com.soundbridge.app.data.SoundBridgeContentProvider;
import com.soundbridge.app.data.SoundBridgeRepository;
import com.soundbridge.app.model.AlertEvent;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Instrumented smoke test verifying application context, SQLite persistence,
 * and SoundBridgeContentProvider query integration on an Android device or emulator.
 */
@RunWith(AndroidJUnit4.class)
public class SoundBridgeInstrumentedTest {

    @Test
    public void useAppContextAndVerifyContentProvider() {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("com.soundbridge.app", appContext.getPackageName());

        AlertDatabaseHelper db = new AlertDatabaseHelper(appContext);
        AlertEvent testEvent = new AlertEvent(
                "evt_inst_test_001",
                "Doorbell (Ding-dong)",
                "Ding-dong",
                AlertEvent.CATEGORY_DOORBELL,
                AlertEvent.SOURCE_BUILT_IN,
                0.91f,
                System.currentTimeMillis(),
                "inst_device",
                false,
                AlertEvent.DELIVERY_LOCAL_ONLY,
                AlertEvent.SYNC_LOCAL_DEMO
        );
        db.upsertAlert(testEvent);

        try (Cursor cursor = appContext.getContentResolver().query(
                SoundBridgeContentProvider.CONTENT_URI_ALERTS,
                null,
                null,
                null,
                null
        )) {
            assertNotNull(cursor);
            assertTrue(cursor.getCount() >= 1);
        }

        assertNotNull(SoundBridgeRepository.getInstance(appContext));
    }
}

