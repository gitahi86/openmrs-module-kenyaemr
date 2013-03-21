/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */

package org.openmrs.module.kenyaemr.report;

import org.apache.commons.lang.time.DateUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.SessionFactory;
import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.Form;
import org.openmrs.Obs;
import org.openmrs.annotation.Handler;
import org.openmrs.api.context.Context;
import org.openmrs.module.kenyaemr.MetadataConstants;
import org.openmrs.module.reporting.cohort.EvaluatedCohort;
import org.openmrs.module.reporting.cohort.definition.AgeCohortDefinition;
import org.openmrs.module.reporting.cohort.definition.CohortDefinition;
import org.openmrs.module.reporting.cohort.definition.CompositionCohortDefinition;
import org.openmrs.module.reporting.cohort.definition.GenderCohortDefinition;
import org.openmrs.module.reporting.cohort.definition.service.CohortDefinitionService;
import org.openmrs.module.reporting.common.DateUtil;
import org.openmrs.module.reporting.evaluation.EvaluationContext;
import org.openmrs.module.reporting.evaluation.EvaluationException;
import org.openmrs.module.reporting.evaluation.parameter.Mapped;
import org.openmrs.module.reporting.evaluation.parameter.Parameter;
import org.openmrs.module.reporting.indicator.Indicator;
import org.openmrs.module.reporting.indicator.SimpleIndicatorResult;

import org.openmrs.module.reporting.indicator.evaluator.IndicatorEvaluator;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * Evaluator for HIV care visit indicators
 */
@Handler(supports = HivCareVisitIndicator.class)
public class HivCareVisitIndicatorEvaluator implements IndicatorEvaluator {

	protected static final Log log = LogFactory.getLog(HivCareVisitIndicatorEvaluator.class);

	@Autowired
	SessionFactory sessionFactory;

	@Override
	public SimpleIndicatorResult evaluate(Indicator indicator, EvaluationContext context) throws EvaluationException {
		HivCareVisitIndicator visitIndicator = (HivCareVisitIndicator) indicator;

		List<Form> hivCareForms = Arrays.asList(
			Context.getFormService().getFormByUuid(MetadataConstants.CLINICAL_ENCOUNTER_HIV_ADDENDUM_FORM_UUID),
			Context.getFormService().getFormByUuid(MetadataConstants.MOH_257_VISIT_SUMMARY_FORM_UUID)
		);

		Date fromDate = visitIndicator.getStartDate();
		Date toDate = DateUtil.getEndOfDayIfTimeExcluded(visitIndicator.getEndDate());

		List<Encounter> hivCareEncounters = Context.getEncounterService().getEncounters(null, null, fromDate, toDate, hivCareForms, null, null, null, null, false);
		List<Encounter> filtered = new ArrayList<Encounter>();

		if (HivCareVisitIndicator.Filter.FEMALES_18_AND_OVER.equals(visitIndicator.getFilter())) {
			EvaluatedCohort females18AndOver = females18AndOver(visitIndicator.getEndDate(), context);

			for (Encounter enc : hivCareEncounters) {
				if (females18AndOver.contains(enc.getPatient().getPatientId())) {
					filtered.add(enc);
				}
			}
		}
		else if (HivCareVisitIndicator.Filter.SCHEDULED.equals(visitIndicator.getFilter())) {
			for (Encounter enc : hivCareEncounters) {
				if (wasScheduledVisit(enc)) {
					filtered.add(enc);
				}
			}
		}
		else if (HivCareVisitIndicator.Filter.UNSCHEDULED.equals(visitIndicator.getFilter())) {
			for (Encounter enc : hivCareEncounters) {
				if (!wasScheduledVisit(enc)) {
					filtered.add(enc);
				}
			}
		}
		else {
			filtered = hivCareEncounters;
		}

		SimpleIndicatorResult result = new SimpleIndicatorResult();
		result.setIndicator(indicator);
		result.setContext(context);
		result.setNumeratorResult(filtered.size());

		return result;
	}

	/**
	 * Evaluates the cohort of females aged 18 and over
	 * @param date the effective date for age calculation
	 * @param context the evaluation context
	 * @return the cohort
	 * @throws EvaluationException
	 */
	private EvaluatedCohort females18AndOver(Date date, EvaluationContext context) throws EvaluationException {
		GenderCohortDefinition females = new GenderCohortDefinition();
		females.setName("Gender = Female");
		females.setFemaleIncluded(true);

		AgeCohortDefinition aged18AndOver = new AgeCohortDefinition();
		aged18AndOver.setName("Age >= 18");
		aged18AndOver.setEffectiveDate(date);
		aged18AndOver.setMinAge(18);

		CompositionCohortDefinition females18AndOver = new CompositionCohortDefinition();
		females18AndOver.addParameter(new Parameter("fromDate", "From Date", Date.class));
		females18AndOver.addParameter(new Parameter("toDate", "To Date", Date.class));
		females18AndOver.addSearch("females", new Mapped<CohortDefinition>(females, null));
		females18AndOver.addSearch("aged18AndOver", new Mapped<CohortDefinition>(aged18AndOver, null));
		females18AndOver.setCompositionString("females AND aged18AndOver");

		return Context.getService(CohortDefinitionService.class).evaluate(females18AndOver, context);
	}

	/**
	 * Determines whether the given encounter was part of a scheduled visit
	 * @param encounter the encounter
	 * @return true if was part of scheduled visit
	 */
	private boolean wasScheduledVisit(Encounter encounter) {
		Date visitDate = (encounter.getVisit() != null) ? encounter.getVisit().getStartDatetime() : encounter.getEncounterDatetime();
		Concept returnVisitDate = Context.getConceptService().getConceptByUuid(MetadataConstants.RETURN_VISIT_DATE_CONCEPT_UUID);
		List<Obs> returnVisitObss = Context.getObsService().getObservationsByPersonAndConcept(encounter.getPatient(), returnVisitDate);

		for (Obs returnVisitObs : returnVisitObss) {
			if (DateUtils.isSameDay(returnVisitObs.getValueDate(), visitDate)) {
				return true;
			}
		}

		return false;
	}
}
