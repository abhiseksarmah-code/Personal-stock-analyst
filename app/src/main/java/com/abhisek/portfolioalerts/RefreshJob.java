package com.abhisek.portfolioalerts;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Runs the hourly refresh in the background. */
public class RefreshJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            boolean retry = false;
            try {
                Engine.run(getApplicationContext(), true);
            } catch (Exception e) {
                retry = true;
            }
            jobFinished(params, retry);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
