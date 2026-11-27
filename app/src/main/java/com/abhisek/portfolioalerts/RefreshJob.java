package com.abhisek.portfolioalerts;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * Hourly background run. Open markets get fresh prices and signals; closed markets only re-check
 * the plan (one small download) and refresh if it changed. Every 6 hours it also looks for a new
 * version of the app and installs it.
 */
public class RefreshJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            try {
                Engine.runAll(getApplicationContext(), true, true);
                Updater.check(getApplicationContext(), Updater.BACKGROUND_GAP);
            } finally {
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
