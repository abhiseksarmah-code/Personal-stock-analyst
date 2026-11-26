package com.abhisek.portfolioalerts;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Runs the hourly refresh in the background. */
public class RefreshJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        if (!Engine.anyMarketOpen()) return false;
        new Thread(() -> {
            Engine.runAll(getApplicationContext(), true, true);
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
