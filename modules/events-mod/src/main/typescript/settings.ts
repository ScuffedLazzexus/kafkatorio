import {SettingsData, ActiveMods} from "factorio:common"
import KafkatorioSettings from "./main/settings/KafkatorioSettings";

declare const data: SettingsData
declare const mods: ActiveMods

KafkatorioSettings.initialiseSettings(data)
