\name{lwbgt_input}
\alias{lwbgt_input}
\title{Construct WBGT Input Records}
\usage{
lwbgt_input(
  year, month, day, hour, minute, gmt_offset_hours, averaging_minutes,
  urban, latitude_deg_north, longitude_deg_east, solar_w_m2,
  pressure_hpa, air_temperature_c, relative_humidity_percent,
  wind_speed_m_s, wind_height_m, vertical_temperature_difference_c
)
}
\arguments{
\item{year}{Integer-valued Gregorian year from 1950 through 2049.}
\item{month, day, hour, minute}{Integer-valued local date and time fields.
A \code{month} of zero makes \code{day} a day-of-year value.}
\item{gmt_offset_hours}{Local standard time minus GMT, in whole hours.}
\item{averaging_minutes}{Input averaging interval, in minutes.}
\item{urban}{Zero for rural wind scaling or one for urban wind scaling.}
\item{latitude_deg_north, longitude_deg_east}{Coordinates in degrees north and
degrees east.}
\item{solar_w_m2}{Solar irradiance in watts per square metre.}
\item{pressure_hpa}{Barometric pressure in hectopascals.}
\item{air_temperature_c}{Dry-bulb air temperature in degrees Celsius.}
\item{relative_humidity_percent}{Relative humidity in percent.}
\item{wind_speed_m_s}{Wind speed in metres per second.}
\item{wind_height_m}{Wind measurement height in metres.}
\item{vertical_temperature_difference_c}{Upper-minus-lower temperature
difference in degrees Celsius. Only whether it is \code{< 0} or \code{>= 0} is
used. It can affect results only for rural nighttime records when
\code{wind_height_m} differs from 2 m and \code{wind_speed_m_s} is below 2.5.}
}
\value{A plain data frame with the 17 native input columns.}
\description{
Constructs one or more input rows. All arguments are required; scalar values
are recycled to the common non-scalar length.
}
\examples{
input <- lwbgt_input(
  year = 2024, month = 4, day = 15, hour = 14, minute = 30,
  gmt_offset_hours = 8, averaging_minutes = 60, urban = 1,
  latitude_deg_north = 1.3521, longitude_deg_east = 103.8198,
  solar_w_m2 = 742, pressure_hpa = 1008.4,
  air_temperature_c = 32.1, relative_humidity_percent = 68,
  wind_speed_m_s = 2.8, wind_height_m = 10,
  vertical_temperature_difference_c = 1
)
}
