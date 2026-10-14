package app.xan.music

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class WorkManagerStartupTest {
    private fun attachedApplication(): App {
        val app = App()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = app
        }
        ReflectionHelpers.callInstanceMethod<Void>(
            app, "attach", ReflectionHelpers.ClassParameter.from(Context::class.java, context),
        )
        return app
    }

    @Test
    fun `crash process starts without initializing download recovery`() {
        val app = attachedApplication()
        val boundApplication = ReflectionHelpers.getField<Any>(
            RuntimeEnvironment.getActivityThread(), "mBoundApplication",
        )
        ReflectionHelpers.setField(boundApplication, "processName", "${app.packageName}:crash")
        assertFalse(app.isMainApplicationProcess())
        app.onCreate()
        @Suppress("DEPRECATION")
        assertNull(WorkManagerImpl.getInstance())
    }

    @Test
    fun `work can be enqueued before application onCreate without startup provider`() {
        // Attach the real application, but deliberately do not call onCreate or
        // AndroidX Startup. This used to throw the exception from the crash report.
        val app = attachedApplication()
        val manager = WorkManager.getInstance(app)
        assertSame(manager, WorkManager.getInstance(app))
        assertEquals(app.packageName, app.workManagerConfiguration.defaultProcessName)

        val request = OneTimeWorkRequestBuilder<StartupProbeWorker>()
            .setInitialDelay(1, TimeUnit.DAYS)
            .build()
        manager.enqueue(request).result.get(10, TimeUnit.SECONDS)
        assertEquals(request.id, manager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)!!.id)
        manager.cancelWorkById(request.id).result.get(10, TimeUnit.SECONDS)
    }
}

class StartupProbeWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork() = Result.success()
}
