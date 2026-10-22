package com.azizx25.smartfinancialwellness.salary;

import com.azizx25.smartfinancialwellness.common.web.ApiException;
import org.springframework.http.HttpStatus;

public class SalaryPlanException extends ApiException {

    private SalaryPlanException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static SalaryPlanException notFound() {
        return new SalaryPlanException(
                HttpStatus.NOT_FOUND,
                "SALARY_PLAN_NOT_FOUND",
                "Salary plan not found");
    }

    public static SalaryPlanException invalid(String message) {
        return new SalaryPlanException(
                HttpStatus.BAD_REQUEST,
                "INVALID_SALARY_PLAN",
                message);
    }

    public static SalaryPlanException conflict() {
        return new SalaryPlanException(
                HttpStatus.CONFLICT,
                "SALARY_PLAN_CONFLICT",
                "A salary plan already exists or was updated concurrently");
    }
}
