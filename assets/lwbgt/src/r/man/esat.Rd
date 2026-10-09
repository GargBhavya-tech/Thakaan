\name{esat}
\alias{esat}
\title{Calculate Saturation Vapour Pressure}
\usage{
esat(temperature_k, phase = 0L)
}
\arguments{
\item{temperature_k}{Numeric temperatures in kelvin.}
\item{phase}{Zero for liquid water or one for ice.}
}
\value{A numeric vector of saturation vapour pressures in hectopascals.}
\description{
Calls the native Liljegren saturation-pressure calculation. Missing inputs
propagate to \code{NA}; invalid non-missing inputs produce \code{NA} and one
warning per call.
}
\examples{
esat(c(263.15, 273.15, 303.15))
esat(263.15, phase = 1L)
}
