package app.aaps.workflow

import android.content.Context
import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.graph.data.BarGraphSeries
import app.aaps.core.graph.data.DataPointWithLabelInterface
import app.aaps.core.graph.data.DeviationDataPoint
import app.aaps.core.graph.data.FixedLineGraphSeries
import app.aaps.core.graph.data.LineGraphSeries
import app.aaps.core.graph.data.PointsWithLabelGraphSeries
import app.aaps.core.graph.data.ScaledDataPoint
import app.aaps.core.graph.data.Shape
import app.aaps.core.interfaces.aps.AutosensData
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.graph.Scale
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.OverviewMenus
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventIobCalculationProgress
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.core.objects.extensions.combine
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.utils.receivers.DataWorkerStorage
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PrepareIobAutosensGraphDataWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.Default) {

    @Inject lateinit var dataWorkerStorage: DataWorkerStorage
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var overviewMenus: OverviewMenus
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var decimalFormatter: DecimalFormatter
    private var ctx: Context

    init {
        ctx = rh.getThemedCtx(context)
    }

    class PrepareIobAutosensData(
        val iobCobCalculator: IobCobCalculator, // cannot be injected : HistoryBrowser uses different instance
        val overviewData: OverviewData
    )

    class IobTotalDataPoint(val i: IobTotal) : DataPointWithLabelInterface {

        private var color = 0
        override fun getX(): Double = i.time.toDouble()
        override fun getY(): Double = i.iob
        override fun setY(y: Double) {}
        override val label = ""
        override val duration = 0L
        override val shape = Shape.IOB_PREDICTION
        override val size = 0.5f
        override val paintStyle: Paint.Style = Paint.Style.FILL

        override fun color(context: Context?): Int = color
        fun setColor(color: Int): IobTotalDataPoint {
            this.color = color
            return this
        }
    }

    class AutosensDataPoint(
        private val ad: AutosensData,
        private val scale: Scale,
        private val chartTime: Long,
        private val rh: ResourceHelper
    ) : DataPointWithLabelInterface {

        override fun getX(): Double = chartTime.toDouble()
        override fun getY(): Double = scale.transform(ad.cob)
        override fun setY(y: Double) {}
        override val label: String = ""
        override val duration = 0L
        override val shape = Shape.COB_FAIL_OVER
        override val size = 0.5f
        override val paintStyle: Paint.Style = Paint.Style.FILL
        override fun color(context: Context?): Int {
            return rh.gac(context, app.aaps.core.ui.R.attr.cobColor)
        }
    }

    override suspend fun doWorkAndLog(): Result {
        val data = dataWorkerStorage.pickupObject(inputData.getLong(DataWorkerStorage.STORE_KEY, -1)) as PrepareIobAutosensData?
            ?: return Result.failure(workDataOf("Error" to "missing input data"))

        val endTime = data.overviewData.endTime
        val fromTime = data.overviewData.fromTime
        rxBus.send(EventIobCalculationProgress(CalculationWorkflow.ProgressData.PREPARE_IOB_AUTOSENS_DATA, 0, null))
        val iobArray: MutableList<ScaledDataPoint> = ArrayList()
        val absIobArray: MutableList<ScaledDataPoint> = ArrayList()
        data.overviewData.maxIobValueFound = Double.MIN_VALUE
        var lastIob = 0.0
        var absLastIob = 0.0
        var time = fromTime

        val minFailOverActiveList: MutableList<DataPointWithLabelInterface> = ArrayList()
        val cobArray: MutableList<ScaledDataPoint> = ArrayList()
        data.overviewData.maxCobValueFound = Double.MIN_VALUE
        var lastCob = 0

        val actArrayHist: MutableList<ScaledDataPoint> = ArrayList()
        val actArrayPrediction: MutableList<ScaledDataPoint> = ArrayList()
        val now = dateUtil.now().toDouble()
        data.overviewData.maxIAValue = 0.0

        val bgiArrayHist: MutableList<ScaledDataPoint> = ArrayList()
        val bgiArrayPrediction: MutableList<ScaledDataPoint> = ArrayList()
        data.overviewData.maxBGIValue = Double.MIN_VALUE

        val devArray: MutableList<DeviationDataPoint> = ArrayList()
        data.overviewData.maxDevValueFound = Double.MIN_VALUE

        val ratioArray: MutableList<ScaledDataPoint> = ArrayList()
        data.overviewData.maxRatioValueFound = 5.0                    //even if sens data equals 0 for all the period, minimum scale is between 95% and 105%
        data.overviewData.minRatioValueFound = -5.0

        val dsMaxArray: MutableList<ScaledDataPoint> = ArrayList()
        val dsMinArray: MutableList<ScaledDataPoint> = ArrayList()
        data.overviewData.maxFromMaxValueFound = Double.MIN_VALUE
        data.overviewData.maxFromMinValueFound = Double.MIN_VALUE

        val adsData = data.iobCobCalculator.ads.clone()

        while (time <= endTime) {
            if (isStopped) return Result.failure(workDataOf("Error" to "stopped"))
            val progress = (time - fromTime).toDouble() / (endTime - fromTime) * 100.0
            rxBus.send(EventIobCalculationProgress(CalculationWorkflow.ProgressData.PREPARE_IOB_AUTOSENS_DATA, progress.toInt(), null))
            val profile = profileFunction.getProfile(time)
            if (profile == null) {
                time += 5 * 60 * 1000L
                continue
            }
            // IOB
            val iob = data.iobCobCalculator.calculateFromTreatmentsAndTemps(time, profile)
            val baseBasalIob = data.iobCobCalculator.calculateAbsoluteIobFromBaseBasals(time)
            val absIob = IobTotal.combine(iob, baseBasalIob)
            val autosensData = adsData.getAutosensDataAtTime(time)
            if (abs(lastIob - iob.iob) > 0.02) {
                if (abs(lastIob - iob.iob) > 0.2) iobArray.add(ScaledDataPoint(time, lastIob, data.overviewData.iobScale))
                iobArray.add(ScaledDataPoint(time, iob.iob, data.overviewData.iobScale))
                data.overviewData.maxIobValueFound = maxOf(data.overviewData.maxIobValueFound, abs(iob.iob))
                lastIob = iob.iob
            }
            if (abs(absLastIob - absIob.iob) > 0.02) {
                if (abs(absLastIob - absIob.iob) > 0.2) absIobArray.add(ScaledDataPoint(time, absLastIob, data.overviewData.iobScale))
                absIobArray.add(ScaledDataPoint(time, absIob.iob, data.overviewData.iobScale))
                data.overviewData.maxIobValueFound = maxOf(data.overviewData.maxIobValueFound, abs(absIob.iob))
                absLastIob = absIob.iob
            }

            // COB
            if (autosensData != null) {
                val cob = autosensData.cob.toInt()
                if (cob != lastCob) {
                    if (autosensData.carbsFromBolus != 0.0) cobArray.add(ScaledDataPoint(time, lastCob.toDouble(), data.overviewData.cobScale))
                    cobArray.add(ScaledDataPoint(time, cob.toDouble(), data.overviewData.cobScale))
                    data.overviewData.maxCobValueFound = max(data.overviewData.maxCobValueFound, cob.toDouble())
                    lastCob = cob
                }
                if (autosensData.failOverToMinAbsorptionRate) {
                    minFailOverActiveList.add(AutosensDataPoint(autosensData, data.overviewData.cobScale, time, rh))
                }
                // BGI
                val devBgiScale = overviewMenus.isEnabledIn(OverviewMenus.CharType.DEV) == overviewMenus.isEnabledIn(OverviewMenus.CharType.BGI)
                val deviation = if (devBgiScale) autosensData.deviation else 0.0
                val sens = autosensData.sens
                val bgi: Double = iob.activity * sens * 5.0
                if (time <= now) bgiArrayHist.add(ScaledDataPoint(time, bgi, data.overviewData.bgiScale))
                else bgiArrayPrediction.add(ScaledDataPoint(time, bgi, data.overviewData.bgiScale))
                data.overviewData.maxBGIValue = max(data.overviewData.maxBGIValue, max(abs(bgi), deviation))

                // DEVIATIONS
                var color = rh.gac(ctx, app.aaps.core.ui.R.attr.deviationBlackColor)  // "="
                if (autosensData.type == "" || autosensData.type == "non-meal") {
                    if (autosensData.pastSensitivity == "C") color = rh.gac(ctx, app.aaps.core.ui.R.attr.deviationGreyColor)
                    if (autosensData.pastSensitivity == "+") color = rh.gac(ctx, app.aaps.core.ui.R.attr.deviationGreenColor)
                    if (autosensData.pastSensitivity == "-") color = rh.gac(ctx, app.aaps.core.ui.R.attr.deviationRedColor)
                } else if (autosensData.type == "uam") {
                    color = rh.gac(ctx, app.aaps.core.ui.R.attr.uamColor)
                } else if (autosensData.type == "csf") {
                    color = rh.gac(ctx, app.aaps.core.ui.R.attr.deviationGreyColor)
                }
                devArray.add(DeviationDataPoint(time.toDouble(), autosensData.deviation, color, data.overviewData.devScale))
                data.overviewData.maxDevValueFound = maxOf(data.overviewData.maxDevValueFound, abs(autosensData.deviation), abs(bgi))
            }

            // ACTIVITY
            if (time <= now) actArrayHist.add(ScaledDataPoint(time, iob.activity, data.overviewData.actScale))
            else actArrayPrediction.add(ScaledDataPoint(time, iob.activity, data.overviewData.actScale))
            data.overviewData.maxIAValue = max(data.overviewData.maxIAValue, abs(iob.activity))

            // RATIO
            if (autosensData != null) {
                ratioArray.add(ScaledDataPoint(time, 100.0 * (autosensData.autosensResult.ratio - 1), data.overviewData.ratioScale))
                data.overviewData.maxRatioValueFound = max(data.overviewData.maxRatioValueFound, 100.0 * (autosensData.autosensResult.ratio - 1))
                data.overviewData.minRatioValueFound = min(data.overviewData.minRatioValueFound, 100.0 * (autosensData.autosensResult.ratio - 1))
            }

            // DEV SLOPE
            if (autosensData != null) {
                dsMaxArray.add(ScaledDataPoint(time, autosensData.slopeFromMaxDeviation, data.overviewData.dsMaxScale))
                dsMinArray.add(ScaledDataPoint(time, autosensData.slopeFromMinDeviation, data.overviewData.dsMinScale))
                data.overviewData.maxFromMaxValueFound = max(data.overviewData.maxFromMaxValueFound, abs(autosensData.slopeFromMaxDeviation))
                data.overviewData.maxFromMinValueFound = max(data.overviewData.maxFromMinValueFound, abs(autosensData.slopeFromMinDeviation))
            }

            time += 5 * 60 * 1000L
        }
        // IOB
        data.overviewData.iobSeries = FixedLineGraphSeries(Array(iobArray.size) { i -> iobArray[i] }).also {
            it.isDrawBackground = true
            it.backgroundColor = -0x7f000001 and rh.gac(ctx, app.aaps.core.ui.R.attr.iobColor)  //50%
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.iobColor)
            it.thickness = 3
        }
        data.overviewData.absIobSeries = FixedLineGraphSeries(Array(absIobArray.size) { i -> absIobArray[i] }).also {
            it.isDrawBackground = true
            it.backgroundColor = -0x7f000001 and rh.gac(ctx, app.aaps.core.ui.R.attr.iobColor) //50%
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.iobColor)
            it.thickness = 3
        }

        if (overviewMenus.setting[0][OverviewMenus.CharType.PRE.ordinal]) {
            val autosensData = adsData.getLastAutosensData("GraphData", aapsLogger, dateUtil)
            val lastAutosensResult = autosensData?.autosensResult ?: AutosensResult()
            val isTempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now()) != null
            val iobPrediction: MutableList<DataPointWithLabelInterface> = ArrayList()
            val iobPredictionArray = data.iobCobCalculator.calculateIobArrayForSMB(lastAutosensResult, SMBDefaults.exercise_mode, SMBDefaults.half_basal_exercise_target, isTempTarget)
            for (i in iobPredictionArray) {
                iobPrediction.add(IobTotalDataPoint(i).setColor(rh.gac(ctx, app.aaps.core.ui.R.attr.iobPredASColor)))
                data.overviewData.maxIobValueFound = max(data.overviewData.maxIobValueFound, abs(i.iob))
            }
            data.overviewData.iobPredictions1Series = PointsWithLabelGraphSeries(Array(iobPrediction.size) { i -> iobPrediction[i] })
            aapsLogger.debug(LTag.AUTOSENS, "IOB prediction for AS=" + decimalFormatter.to2Decimal(lastAutosensResult.ratio) + ": " + data.iobCobCalculator.iobArrayToString(iobPredictionArray))
        } else {
            data.overviewData.iobPredictions1Series = PointsWithLabelGraphSeries<DataPointWithLabelInterface>()
        }

        // COB
        data.overviewData.cobSeries = FixedLineGraphSeries(Array(cobArray.size) { i -> cobArray[i] }).also {
            it.isDrawBackground = true
            it.backgroundColor = -0x7f000001 and rh.gac(ctx, app.aaps.core.ui.R.attr.cobColor) //50%
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.cobColor)
            it.thickness = 3
        }
        data.overviewData.cobMinFailOverSeries = PointsWithLabelGraphSeries(Array(minFailOverActiveList.size) { i -> minFailOverActiveList[i] })

        // ACTIVITY
        data.overviewData.activitySeries = FixedLineGraphSeries(Array(actArrayHist.size) { i -> actArrayHist[i] }).also {
            it.isDrawBackground = false
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.activityColor)
            it.thickness = 3
        }
        data.overviewData.activityPredictionSeries = FixedLineGraphSeries(Array(actArrayPrediction.size) { i -> actArrayPrediction[i] }).also {
            it.setCustomPaint(Paint().also { paint ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                paint.pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
                paint.color = rh.gac(ctx, app.aaps.core.ui.R.attr.activityColor)
            })
        }

        // BGI
        data.overviewData.minusBgiSeries = FixedLineGraphSeries(Array(bgiArrayHist.size) { i -> bgiArrayHist[i] }).also {
            it.isDrawBackground = false
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.bgiColor)
            it.thickness = 3
        }
        data.overviewData.minusBgiHistSeries = FixedLineGraphSeries(Array(bgiArrayPrediction.size) { i -> bgiArrayPrediction[i] }).also {
            it.setCustomPaint(Paint().also { paint ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                paint.pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
                paint.color = rh.gac(ctx, app.aaps.core.ui.R.attr.bgiColor)
            })
        }

        // DEVIATIONS
        data.overviewData.deviationsSeries = BarGraphSeries(Array(devArray.size) { i -> devArray[i] }).also {
            it.setValueDependentColor { data: DeviationDataPoint -> data.color }
        }

        // RATIO
        data.overviewData.ratioSeries = LineGraphSeries(Array(ratioArray.size) { i -> ratioArray[i] }).also {
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.ratioColor)
            it.thickness = 3
        }

        // DEV SLOPE
        data.overviewData.dsMaxSeries = LineGraphSeries(Array(dsMaxArray.size) { i -> dsMaxArray[i] }).also {
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.devSlopePosColor)
            it.thickness = 3
        }
        data.overviewData.dsMinSeries = LineGraphSeries(Array(dsMinArray.size) { i -> dsMinArray[i] }).also {
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.devSlopeNegColor)
            it.thickness = 3
        }

        // VAR_SENS
        val varSensArray: MutableList<ScaledDataPoint> = ArrayList()
        data.overviewData.maxVarSensValueFound = Double.MIN_VALUE
        data.overviewData.minVarSensValueFound = Double.MAX_VALUE
        val apsResults = persistenceLayer.getApsResults(fromTime, endTime)
        apsResults.forEach {
            it.variableSens?.let { variableSens ->
                val varSens = profileUtil.fromMgdlToUnits(variableSens)
                varSensArray.add(ScaledDataPoint(it.date, varSens, data.overviewData.varSensScale))
                data.overviewData.maxVarSensValueFound = max(data.overviewData.maxVarSensValueFound, varSens)
                data.overviewData.minVarSensValueFound = min(data.overviewData.minVarSensValueFound, varSens)
            }
        }
        data.overviewData.varSensSeries = LineGraphSeries(Array(varSensArray.size) { i -> varSensArray[i] }).also {
            it.color = rh.gac(ctx, app.aaps.core.ui.R.attr.ratioColor)
            it.thickness = 3
        }

        rxBus.send(EventIobCalculationProgress(CalculationWorkflow.ProgressData.PREPARE_IOB_AUTOSENS_DATA, 100, null))
        return Result.success()
    }
}