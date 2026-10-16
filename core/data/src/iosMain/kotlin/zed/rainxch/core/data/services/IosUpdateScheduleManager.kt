package zed.rainxch.core.data.services

import zed.rainxch.core.domain.system.UpdateScheduleManager

class IosUpdateScheduleManager : UpdateScheduleManager {
    override fun reschedule(intervalHours: Long) = Unit

    override fun cancel() = Unit
}
