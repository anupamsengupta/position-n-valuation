/** Domain type aliases matching backend enums. */

export type TimeGranularity = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY';

export type SubDailyGranularity = 'MIN_15' | 'MIN_30' | 'HOURLY';

export type PeriodStatus = 'SETTLED' | 'TRANSITION' | 'FORWARD';

export type DeliveryStatus = 'SETTLED' | 'PARTIAL' | 'FORWARD';

export type DayStatus = 'SETTLED' | 'TODAY' | 'FORWARD';
