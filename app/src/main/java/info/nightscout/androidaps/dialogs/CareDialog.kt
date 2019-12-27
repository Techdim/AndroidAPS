package info.nightscout.androidaps.dialogs

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import com.google.common.base.Joiner
import info.nightscout.androidaps.Constants
import info.nightscout.androidaps.MainApp
import info.nightscout.androidaps.R
import info.nightscout.androidaps.data.Profile
import info.nightscout.androidaps.db.CareportalEvent
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions
import info.nightscout.androidaps.plugins.general.nsclient.NSUpload
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatus
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.HtmlHelper
import info.nightscout.androidaps.utils.OKDialog
import info.nightscout.androidaps.utils.SP
import info.nightscout.androidaps.utils.Translator
import info.nightscout.androidaps.utils.resources.ResourceHelper
import kotlinx.android.synthetic.main.dialog_care.*
import kotlinx.android.synthetic.main.notes.*
import kotlinx.android.synthetic.main.okcancel.*
import org.json.JSONObject
import java.text.DecimalFormat
import java.util.*
import javax.inject.Inject

class CareDialog : DialogFragmentWithDate() {

    @Inject
    lateinit var mainApp: MainApp

    @Inject
    lateinit var resourceHelper: ResourceHelper

    enum class EventType {
        BGCHECK,
        SENSOR_INSERT,
        BATTERY_CHANGE
    }

    private var options: EventType = EventType.BGCHECK
    @StringRes
    private var event: Int = R.string.none

    fun setOptions(options: EventType, @StringRes event: Int): CareDialog {
        this.options = options
        this.event = event
        return this
    }

    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        savedInstanceState.putDouble("actions_care_bg", actions_care_bg.value)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        onCreateViewGeneral()
        return inflater.inflate(R.layout.dialog_care, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        actions_care_icon.setImageResource(when (options) {
            EventType.BGCHECK        -> R.drawable.icon_cp_bgcheck
            EventType.SENSOR_INSERT  -> R.drawable.icon_cp_cgm_insert
            EventType.BATTERY_CHANGE -> R.drawable.icon_cp_pump_battery
        })
        actions_care_title.text = resourceHelper.gs(when (options) {
            EventType.BGCHECK        -> R.string.careportal_bgcheck
            EventType.SENSOR_INSERT  -> R.string.careportal_cgmsensorinsert
            EventType.BATTERY_CHANGE -> R.string.careportal_pumpbatterychange
        })

        when (options) {
            EventType.SENSOR_INSERT,
            EventType.BATTERY_CHANGE -> {
                action_care_bg_layout.visibility = View.GONE
                actions_care_bgsource.visibility = View.GONE
            }

            else                     -> {
            }
        }

        val bg = Profile.fromMgdlToUnits(GlucoseStatus.getGlucoseStatusData()?.glucose
            ?: 0.0, ProfileFunctions.getSystemUnits())
        val bgTextWatcher: TextWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable) {}
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (actions_care_sensor.isChecked) actions_care_meter.isChecked = true
            }
        }

        if (ProfileFunctions.getSystemUnits() == Constants.MMOL) {
            actions_care_bgunits.text = resourceHelper.gs(R.string.mmol)
            actions_care_bg.setParams(savedInstanceState?.getDouble("actions_care_bg")
                ?: bg, 2.0, 30.0, 0.1, DecimalFormat("0.0"), false, ok, bgTextWatcher)
        } else {
            actions_care_bgunits.text = resourceHelper.gs(R.string.mgdl)
            actions_care_bg.setParams(savedInstanceState?.getDouble("actions_care_bg")
                ?: bg, 36.0, 500.0, 1.0, DecimalFormat("0"), false, ok, bgTextWatcher)
        }
    }

    override fun submit(): Boolean {
        val enteredBy = SP.getString("careportal_enteredby", "")
        val unitResId = if (ProfileFunctions.getSystemUnits() == Constants.MGDL) R.string.mgdl else R.string.mmol

        val json = JSONObject()
        val actions: LinkedList<String> = LinkedList()
        if (options == EventType.BGCHECK) {
            val type =
                when {
                    actions_care_meter.isChecked  -> "Finger"
                    actions_care_sensor.isChecked -> "Sensor"
                    else                          -> "Manual"
                }
            actions.add(resourceHelper.gs(R.string.careportal_newnstreatment_glucosetype) + ": " + Translator.translate(type))
            actions.add(resourceHelper.gs(R.string.treatments_wizard_bg_label) + ": " + Profile.toCurrentUnitsString(actions_care_bg.value) + " " + resourceHelper.gs(unitResId))
            json.put("glucose", actions_care_bg.value)
            json.put("glucoseType", type)
        }
        val notes = notes.text.toString()
        if (notes.isNotEmpty()) {
            actions.add(resourceHelper.gs(R.string.careportal_newnstreatment_notes_label) + ": " + notes)
            json.put("notes", notes)
        }
        if (eventTimeChanged)
            actions.add(resourceHelper.gs(R.string.time) + ": " + DateUtil.dateAndTimeString(eventTime))

        json.put("created_at", DateUtil.toISOString(eventTime))
        json.put("eventType", when (options) {
            EventType.BGCHECK        -> CareportalEvent.BGCHECK
            EventType.SENSOR_INSERT  -> CareportalEvent.SENSORCHANGE
            EventType.BATTERY_CHANGE -> CareportalEvent.PUMPBATTERYCHANGE
        })
        json.put("units", ProfileFunctions.getSystemUnits())
        if (enteredBy.isNotEmpty())
            json.put("enteredBy", enteredBy)

        activity?.let { activity ->
            OKDialog.showConfirmation(activity, resourceHelper.gs(event), HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)), Runnable {
                MainApp.getDbHelper().createCareportalEventFromJsonIfNotExists(json)
                NSUpload.uploadCareportalEntryToNS(json)
            }, null)
        }
        return true
    }
}
