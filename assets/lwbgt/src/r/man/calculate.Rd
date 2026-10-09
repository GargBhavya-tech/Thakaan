\name{calculate}
\alias{calculate}
\title{Calculate Outdoor Wet Bulb Globe Temperature}
\usage{
calculate(input, psychrometric = TRUE)
}
\arguments{
\item{input}{A data frame containing the columns produced by
\code{lwbgt_input()}. Additional columns are ignored.}
\item{psychrometric}{Calculate psychrometric wet-bulb temperature, default
\code{TRUE}. With \code{FALSE}, that column is \code{NA} and other results
are unchanged.}
}
\value{
A plain data frame containing integer \code{status}, character
\code{status_message}, and five numeric result columns. Status zero indicates
success; -1 indicates solver non-convergence; 1 indicates missing input; 2
indicates invalid input; and 3 indicates an invalid native result. Numeric
outputs are \code{NA} for nonzero statuses.
}
\description{
Calculates one result per input row while preserving row order. Invalid rows do
not prevent valid rows from being calculated.
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
calculate(input)
}
