import CLWBGT
import XCTest

final class CLWBGTConsumerTests: XCTestCase {
    func testNumericalCorpus() throws {
        let environment = ProcessInfo.processInfo.environment
        guard let casesPath = environment["LWBGT_CASES"],
              let expectedPath = environment["LWBGT_EXPECTED"] else {
            throw XCTSkip("Set LWBGT_CASES and LWBGT_EXPECTED for the CMake comparison")
        }
        let cases = try String(contentsOfFile: casesPath, encoding: .utf8)
            .split(separator: "\n").dropFirst()
        let expected = try String(contentsOfFile: expectedPath, encoding: .utf8)
            .split(separator: "\n").dropFirst()
        XCTAssertFalse(cases.isEmpty)
        XCTAssertEqual(cases.count, expected.count)

        for (row, reference) in zip(cases, expected) {
            let fields = row.split(separator: ",")
            let answer = reference.split(separator: ",")
            XCTAssertEqual(fields.count, 19)
            XCTAssertEqual(answer.count, 8)
            guard fields.count == 19, answer.count == 8 else { return }
            XCTAssertEqual(fields[0], answer[0])

            func integer(_ column: Int) throws -> Int32 {
                try XCTUnwrap(Int32(fields[column]))
            }
            func number(_ column: Int) throws -> Double {
                try XCTUnwrap(Double(fields[column]))
            }

            var input = lwbgt_input_v1()
            input.year = try integer(2)
            input.month = try integer(3)
            input.day = try integer(4)
            input.hour = try integer(5)
            input.minute = try integer(6)
            input.gmt_offset_hours = try integer(7)
            input.averaging_minutes = try integer(8)
            input.latitude_deg_north = try number(9)
            input.longitude_deg_east = try number(10)
            input.solar_w_m2 = try number(11)
            input.pressure_hpa = try number(12)
            input.air_temperature_c = try number(13)
            input.relative_humidity_percent = try number(14)
            input.wind_speed_m_s = try number(15)
            input.wind_height_m = try number(16)
            input.vertical_temperature_difference_c = try number(17)
            input.urban = try integer(18)
            var output = lwbgt_output_v1()
            XCTAssertEqual(lwbgt_calc_batch_v1(&input, &output, 1), Int32(LWBGT_BATCH_OK))
            XCTAssertEqual(output.status, try XCTUnwrap(Int32(answer[1])), String(fields[0]))
            let values = [
                output.estimated_wind_speed_m_s, output.globe_temperature_c,
                output.natural_wet_bulb_c, output.psychrometric_wet_bulb_c,
                output.wbgt_c, esat(input.air_temperature_c + 273.15, 0),
            ]
            for (value, bits) in zip(values, answer.dropFirst(2)) {
                XCTAssertEqual(value.bitPattern, try XCTUnwrap(UInt32(bits, radix: 16)),
                               String(fields[0]))
            }
        }
    }

    func testAbiAndLayout() {
        XCTAssertEqual(LWBGT_FFI_ABI_VERSION, 1)
        XCTAssertEqual(MemoryLayout<lwbgt_input_v1>.size, 104)
        XCTAssertEqual(MemoryLayout<lwbgt_output_v1>.size, 24)
    }

    func testBatchAndEsat() {
        var input = lwbgt_input_v1()
        input.year = 2024
        input.month = 4
        input.day = 15
        input.hour = 14
        input.minute = 30
        input.gmt_offset_hours = 8
        input.averaging_minutes = 60
        input.urban = 1
        input.latitude_deg_north = 1.3521
        input.longitude_deg_east = 103.8198
        input.solar_w_m2 = 742.0
        input.pressure_hpa = 1008.4
        input.air_temperature_c = 32.1
        input.relative_humidity_percent = 68.0
        input.wind_speed_m_s = 2.8
        input.wind_height_m = 10.0
        input.vertical_temperature_difference_c = -0.4
        var output = lwbgt_output_v1()

        XCTAssertEqual(
            lwbgt_calc_batch_v1(&input, &output, 1),
            Int32(LWBGT_BATCH_OK)
        )
        XCTAssertEqual(output.status, 0)
        XCTAssertEqual(output.wbgt_c.bitPattern, 0x42020259)
        XCTAssertEqual(esat(273.15, 0).bitPattern, 0x40c45e95)
        let full = output
        XCTAssertEqual(
            lwbgt_calc_batch_ex_v1(&input, &output, 1,
                UInt32(LWBGT_SKIP_PSYCHROMETRIC_WET_BULB)),
            Int32(LWBGT_BATCH_OK)
        )
        XCTAssertEqual(output.psychrometric_wet_bulb_c, -9999)
        XCTAssertEqual(output.status, full.status)
        XCTAssertEqual(output.estimated_wind_speed_m_s.bitPattern, full.estimated_wind_speed_m_s.bitPattern)
        XCTAssertEqual(output.globe_temperature_c.bitPattern, full.globe_temperature_c.bitPattern)
        XCTAssertEqual(output.natural_wet_bulb_c.bitPattern, full.natural_wet_bulb_c.bitPattern)
        XCTAssertEqual(output.wbgt_c.bitPattern, full.wbgt_c.bitPattern)
    }

    func testBatchArgumentContract() {
        var output = lwbgt_output_v1()

        XCTAssertEqual(
            lwbgt_calc_batch_v1(nil, nil, 0),
            Int32(LWBGT_BATCH_OK)
        )
        XCTAssertEqual(
            lwbgt_calc_batch_v1(nil, &output, 1),
            Int32(LWBGT_BATCH_INVALID_ARGUMENT)
        )
    }
}
