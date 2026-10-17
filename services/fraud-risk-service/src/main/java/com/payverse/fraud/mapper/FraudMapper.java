package com.payverse.fraud.mapper;

import com.payverse.fraud.dto.AssessResponse;
import com.payverse.fraud.dto.AssessmentResponse;
import com.payverse.fraud.dto.FlagResponse;
import com.payverse.fraud.dto.RiskRuleResponse;
import com.payverse.fraud.entity.Flag;
import com.payverse.fraud.entity.RiskAssessment;
import com.payverse.fraud.entity.RiskRuleConfig;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface FraudMapper {

    @Mapping(target = "assessmentId", source = "id")
    @Mapping(target = "reasons", expression = "java(assessment.reasonList())")
    AssessResponse toAssessResponse(RiskAssessment assessment);

    @Mapping(target = "type", source = "txnType")
    @Mapping(target = "reasons", expression = "java(assessment.reasonList())")
    AssessmentResponse toResponse(RiskAssessment assessment);

    FlagResponse toResponse(Flag flag);

    RiskRuleResponse toResponse(RiskRuleConfig config);
}
