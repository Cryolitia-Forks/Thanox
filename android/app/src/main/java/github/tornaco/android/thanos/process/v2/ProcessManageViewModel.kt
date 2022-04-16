package github.tornaco.android.thanos.process.v2

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.text.format.Formatter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elvishew.xlog.XLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import github.tornaco.android.thanos.core.T
import github.tornaco.android.thanos.core.app.ThanosManager
import github.tornaco.android.thanos.core.pm.PREBUILT_PACKAGE_SET_ID_3RD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.*
import javax.inject.Inject

@SuppressLint("StaticFieldLeak")
@HiltViewModel
class ProcessManageViewModel @Inject constructor(@ApplicationContext private val context: Context) :
    ViewModel() {
    private val _state =
        MutableStateFlow(
            ProcessManageState(
                isLoading = true,
                selectedAppSetFilterItem = null,
                runningAppStates = emptyList(),
                runningAppStatesBg = emptyList(),
                appFilterItems = emptyList(),
                appsNotRunning = emptyList()
            )
        )
    val state = _state.asStateFlow()

    private val thanox by lazy { ThanosManager.from(context) }
    private val pm by lazy { context.packageManager }

    fun init() {
        viewModelScope.launch {
            loadDefaultAppFilterItems()
            loadProcessStates()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            loadProcessStates()
        }
    }

    private suspend fun loadProcessStates() = withContext(Dispatchers.IO) {
        updateLoadingState(true)

        val filterPackages = state.value.selectedAppSetFilterItem?.let {
            thanox.pkgManager.getPackageSetById(it.id, true).pkgNames
        } ?: emptyList()

        val runningServices = thanox.activityManager.getRunningServiceLegacy(Int.MAX_VALUE)
        val runningAppProcess =
            thanox.activityManager.runningAppProcessLegacy.filter { it.pkgList != null && it.pkgList.isNotEmpty() }
        val runningPackages = runningAppProcess.map { it.pkgList[0] }.distinct()

        val runningAppStates = runningAppProcess.groupBy { it.pkgList[0] }.map { entry ->
            val pkgName = entry.key
            val runningProcessStates = entry.value.map { process ->
                val processPss =
                    thanox.activityManager.getProcessPss(intArrayOf(process.pid)).sum()
                RunningProcessState(
                    process = process,
                    runningServices = runningServices.filter { service ->
                        service.pid == process.pid
                    }.map {
                        val label = getServiceLabel(it)
                        val clientLabel = if (it.clientPackage != null && it.clientLabel > 0) {
                            val clientR: Resources = pm.getResourcesForApplication(it.clientPackage)
                            clientR.getString(it.clientLabel)
                        } else {
                            null
                        }
                        RunningService(it, label, clientLabel)
                    },
                    sizeStr = Formatter.formatShortFileSize(context, processPss * 1024),
                )
            }.sortedByDescending { it.runningServices.size }
            val isAllProcessCached =
                entry.value.all { it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED }
            val totalPss =
                thanox.activityManager.getProcessPss(entry.value.map { it.pid }.toIntArray()).sum()
            RunningAppState(
                appInfo = thanox.pkgManager.getAppInfo(pkgName),
                processState = runningProcessStates,
                allProcessIsCached = isAllProcessCached,
                totalPss = totalPss,
                sizeStr = Formatter.formatShortFileSize(context, totalPss * 1024)
            )
        }.filter {
            filterPackages.contains(it.appInfo.pkgName)
        }.sortedByDescending { it.totalPss }

        val runningAppStatesGroupByCached = runningAppStates.groupBy { it.allProcessIsCached }
        XLog.d("startLoading: %s", runningAppStatesGroupByCached)

        val notRunningApps = filterPackages.filterNot { runningPackages.contains(it) }.map {
            thanox.pkgManager.getAppInfo(it)
        }.sortedWith { o1, o2 ->
            Collator.getInstance(Locale.CHINESE).compare(o1.appLabel, o2.appLabel)
        }

        _state.value = _state.value.copy(
            isLoading = false,
            runningAppStates = runningAppStatesGroupByCached[false] ?: emptyList(),
            runningAppStatesBg = runningAppStatesGroupByCached[true] ?: emptyList(),
            appsNotRunning = notRunningApps
        )
    }

    private fun getServiceLabel(runningService: ActivityManager.RunningServiceInfo): String {
        return kotlin.runCatching {
            val serviceInfo = context.packageManager.getServiceInfo(runningService.service, 0)
            return if (serviceInfo.labelRes != 0 || serviceInfo.nonLocalizedLabel != null) {
                serviceInfo.loadLabel(context.packageManager).toString()
            } else {
                runningService.service.className.substringAfterLast(".")
            }
        }.getOrElse {
            runningService.service.className.substringAfterLast(".")
        }
    }


    private suspend fun loadDefaultAppFilterItems() {
        val appFilterListItems = Loader.loadAllFromAppSet(context)
        _state.value = _state.value.copy(
            // Default select 3-rd
            selectedAppSetFilterItem = appFilterListItems.find {
                it.id == PREBUILT_PACKAGE_SET_ID_3RD
            },
            appFilterItems = appFilterListItems
        )
    }

    private fun updateLoadingState(isLoading: Boolean) {
        _state.value = _state.value.copy(isLoading = isLoading)
    }

    fun onFilterItemSelected(appSetFilterItem: AppSetFilterItem) {
        _state.value = _state.value.copy(selectedAppSetFilterItem = appSetFilterItem)
        refresh()
    }

    fun clearBgTasks() {
        viewModelScope.launch {
            context.sendBroadcast(Intent(T.Actions.ACTION_RUNNING_PROCESS_CLEAR))
            updateLoadingState(true)
            delay(1000)
            refresh()
        }
    }
}