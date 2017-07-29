package us.ceka.service.impl;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.easyrules.api.Rule;
import org.easyrules.api.RulesEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import us.ceka.dao.FootballMatchDao;
import us.ceka.dao.FootballOddsDao;
import us.ceka.dao.FootballStandingDao;
import us.ceka.dao.FootballTierDao;
import us.ceka.domain.Analytics;
import us.ceka.domain.FootballLeague;
import us.ceka.domain.FootballMatch;
import us.ceka.domain.FootballOdds;
import us.ceka.domain.FootballStanding;
import us.ceka.domain.FootballTier;
import us.ceka.domain.FootballMatchup;
import us.ceka.domain.KeysObject;
import us.ceka.dto.FootballMatchDto;
import us.ceka.rule.AbnormalRateRule;
import us.ceka.rule.BigRateChangeRule;
import us.ceka.rule.RankMatchUpRule;
import us.ceka.rule.RepetitiveResultRule;
import us.ceka.service.FootballOddsAnalyseService;

@Service("footballOddsAnalyseService")
@Transactional
public class FootballOddsAnalyseServiceImpl extends GenericServiceImpl implements FootballOddsAnalyseService{
	@Autowired
	private FootballOddsDao footballOddsDao;
	@Autowired
	private FootballMatchDao footballMatchDao;
	@Autowired
	private FootballStandingDao footballStandingDao;
	@Autowired
	private FootballTierDao footballTierDao;

	@Autowired
	private FootballMatchDto footballMatchDto;
	
	@Autowired
	private RulesEngine rulesEngine;
	
	public void executeOddsAnalysis() {
		for(FootballMatch match : footballMatchDao.getLatestMatch()) {
			FootballOdds latestOdds = footballOddsDao.findRecentOddsRecord(match.getMatchId());
			FootballOdds initialOdds = footballOddsDao.findInitialOddsRecord(match.getMatchId());
			FootballStanding homeStanding = footballStandingDao.getStanding(match.getLeague().name(), match.getSeason(), match.getHomeTeam());
			FootballStanding awayStanding = footballStandingDao.getStanding(match.getLeague().name(), match.getSeason(), match.getAwayTeam());
			Map<String, Object> matchupStat = footballMatchDto.getMatchUpStat(match);
			
			FootballTier homeTier = footballTierDao.getByKey(match.getHomeTeam());
			FootballTier awayTier = footballTierDao.getByKey(match.getAwayTeam());
			
			List<FootballMatch> homeTeamPastMatches = footballMatchDao.getLastMatches(match.getHomeTeam(), FootballMatch.MATCH_AT.HOME, 10); 
			List<FootballMatch> awayTeamPastMatches = footballMatchDao.getLastMatches(match.getAwayTeam(), FootballMatch.MATCH_AT.AWAY, 10);
			Collections.reverse(homeTeamPastMatches);
			Collections.reverse(awayTeamPastMatches);
			
			List<FootballMatchup> homeMatchups = footballMatchDao.getMatchup(match.getHomeTeam(), match.getLeague().name(), FootballMatch.MATCH_AT.HOME);
			List<FootballMatchup> awayMatchups = footballMatchDao.getMatchup(match.getAwayTeam(), match.getLeague().name(), FootballMatch.MATCH_AT.AWAY);

			
			log.info("Analysing [{}] [{} {}] {} ({}) vs {} ({}) [{}, {}, {}]", match.getMatchId(), match.getMatchDate(), match.getMatchDay(), match.getHomeTeam(), 
					match.getLeague().isDomesticLeague() ? homeStanding.getRank() : "-",
					match.getAwayTeam(), 
					match.getLeague().isDomesticLeague() ? awayStanding.getRank() : "-",
					latestOdds.getHomeRate(), latestOdds.getDrawRate(), latestOdds.getAwayRate());
			
			rulesEngine.registerRule(new AbnormalRateRule().setInput(match, latestOdds, matchupStat));
			rulesEngine.registerRule(new BigRateChangeRule().setIntput(match, initialOdds, latestOdds));
			rulesEngine.registerRule(new RankMatchUpRule().setInput(match, latestOdds, homeStanding, awayStanding, homeTier, awayTier));
			rulesEngine.registerRule(new RepetitiveResultRule().setInput(match, homeTeamPastMatches, awayTeamPastMatches));
			
			rulesEngine.fireRules();
			
			boolean abnormalRateFound = false;
			for(Map.Entry<Rule, Boolean> rule : rulesEngine.checkRules().entrySet()) { 
				if(Boolean.TRUE.equals(rule.getValue())) {
					abnormalRateFound = true;
					//insert into database
				}
			}
			if(abnormalRateFound) {
				Map<KeysObject, Integer> homeMatchesInSeason = homeMatchups.stream().collect(Collectors.groupingBy(m -> new KeysObject(m.getSeason(), m.getVsTier()), 
						Collectors.summingInt(FootballMatchup::getNumMatches)));

				for(FootballMatchup matchup : homeMatchups) {
					double rate = matchup.getNumMatches() / (double) homeMatchesInSeason.get(new KeysObject(matchup.getSeason(), matchup.getVsTier()));
					log.info("{}(T:{}) Opp.T:{} {} {}:{} total:{} Rate:{} vs:{}{}", matchup.getTeam(), matchup.getTeamTier(), matchup.getVsTier(), 
							String.format("%-10s", matchup.getSeason()), matchup.getResult(), 
							matchup.getNumMatches(), homeMatchesInSeason.get(new KeysObject(matchup.getSeason(), matchup.getVsTier())), String.format("%.2f%%", rate*100), matchup.getVsTeamList(), 
							matchup.getVsRankList() == null ? "" : matchup.getVsRankList());
				}
				
				Map<KeysObject, Integer> awayMatchesInSeason = awayMatchups.stream().collect(Collectors.groupingBy(m -> new KeysObject(m.getSeason(), m.getVsTier()), 
						Collectors.summingInt(FootballMatchup::getNumMatches)));
				
				for(FootballMatchup matchup : awayMatchups) {
					double rate = matchup.getNumMatches() / (double) awayMatchesInSeason.get(new KeysObject(matchup.getSeason(), matchup.getVsTier()));
					log.info("{}(T:{}) Opp.T:{} {} {}:{} total:{} Rate:{} vs:{}{}", matchup.getTeam(), matchup.getTeamTier(), matchup.getVsTier(), 
							String.format("%-10s", matchup.getSeason()), matchup.getResult(), 
							matchup.getNumMatches(), awayMatchesInSeason.get(new KeysObject(matchup.getSeason(), matchup.getVsTier())), String.format("%.2f%%", rate*100), matchup.getVsTeamList(), 
							matchup.getVsRankList() == null ? "" : matchup.getVsRankList());
				}
			}
			rulesEngine.clearRules();
				
		}
	}

	@Deprecated
	public void executeAnalyseOdds() {

		//executeAnalyseMatchup("阿仙奴", "車路士", FootballLeague.ENG_PREMIER_LEAGUE);
		//List<FootballMatch> homeTeamPastMatches = footballMatchDao.getLastMatches("阿仙奴", true, 10);
		 
		for(FootballMatch match : footballMatchDao.getLatestMatch()) {
			FootballOdds latestOdds = footballOddsDao.findRecentOddsRecord(match.getMatchId());
			FootballOdds initialOdds = footballOddsDao.findInitialOddsRecord(match.getMatchId());
			log.info("Analysing [{}] {} vs {} [{}, {}, {}]", match.getMatchId(), match.getHomeTeam(), match.getAwayTeam(),
					latestOdds.getHomeRate(), latestOdds.getDrawRate(), latestOdds.getAwayRate());
			
			boolean analyseMatchup = false;
			
			if(latestOdds.getAwayRate().compareTo(new BigDecimal(Analytics.HIGH_AWAY_ODDS.getValue())) > 0) {
				analyseMatchup = true;
			}
			if(latestOdds.getHomeRate().compareTo(new BigDecimal(Analytics.HIGH_HOME_ODDS.getValue())) > 0) {
				analyseMatchup = true;
			}
			/*
			int homeMatchCount = 0, homeGoalsFor = 0, homeGoalsAgainst = 0, awayMatchCount = 0, awayGoalsFor = 0, awayGoalsAginst = 0;
			List<FootballMatch> homeTeamPastMatches = footballMatchDao.getLastMatches(match.getHomeTeam(), FootballMatch.MATCH_AT.HOME, 10);
			for(FootballMatch homeMatch : homeTeamPastMatches) {
				if(!FootballLeague.TYPE.OTHERS.equals(homeMatch.getLeague().getType())) {
					homeMatchCount++;
					homeGoalsFor += homeMatch.getHomeScore();
					homeGoalsAgainst += homeMatch.getAwayScore();
				}
			}
			if(homeMatchCount != 0) {
				float homeTeamAverageGoalsFor =  homeGoalsFor / (float)homeMatchCount;
				float homeTeamAverageGoalAgainst = homeGoalsAgainst / (float) homeMatchCount;
			}
			
			List<FootballMatch> awayTeamPastMatches = footballMatchDao.getLastMatches(match.getAwayTeam(), FootballMatch.MATCH_AT.AWAY, 10);
			for(FootballMatch awayMatch : awayTeamPastMatches) {
				if(!FootballLeague.TYPE.OTHERS.equals(awayMatch.getLeague().getType())) {
					awayMatchCount++;
					awayGoalsFor += awayMatch.getAwayScore();
					awayGoalsAginst += awayMatch.getHomeScore();
				}
			}
			if(awayMatchCount != 0) {
				float awayTeamAverageGoalsFor =  awayGoalsFor / (float)awayMatchCount;
				float awayTeamAverageGoalsAgainst =  awayGoalsAginst / (float)awayMatchCount;
			}
*/
			if(!StringUtils.equals(latestOdds.getHandicapLine(), initialOdds.getHandicapLine())) {
				log.info("***handicap line change...");
			}
			if(latestOdds.getAwayRate().subtract(initialOdds.getAwayRate()).abs()
					.compareTo(new BigDecimal(Analytics.BIG_RATE_CHANGE.getValue()) ) > 0) {
				log.info("***[{} {}] Away Rate ({},{} vs {}) has {} {} [H:{} D:{} A:{}]",  
						match.getMatchDate(), match.getMatchDay(), match.getMatchId(), match.getHomeTeam(), match.getAwayTeam(),
						latestOdds.getAwayRate().compareTo(initialOdds.getAwayRate()) > 0 ? "increased" : "decreased",
						latestOdds.getAwayRate().subtract(initialOdds.getAwayRate()).abs(),
						latestOdds.getHomeRate(), latestOdds.getDrawRate(), latestOdds.getAwayRate() 
						);
				analyseMatchup = true;
			}
			
			Map<String, Object> map = footballMatchDto.getMatchUpStat(match);
			int vsWin = map.get("vsWin") == null ? 0 : Integer.parseInt((String)map.get("vsWin"));
			int vsDraw = map.get("vsDraw") == null ? 0 : Integer.parseInt((String)map.get("vsDraw"));
			int vsLose = map.get("vsLose") == null ? 0 : Integer.parseInt((String)map.get("vsLose"));
			
			if(vsWin + vsDraw + vsLose > Analytics.MIN_REFERENCE_MATCHES.getValue()) {
				if(latestOdds.getHomeRate().compareTo(new BigDecimal(Analytics.DOUBT_RATE.getValue())) > 0) {			
					if((vsWin + vsDraw) / (double) (vsWin + vsDraw + vsLose) > Analytics.HIGH_WIN_POSSIBILITY.getValue()) {
						log.info("***[{} {}] Abnormal Home Rate {} ({},{} vs {}) with {}win, {}draw out of {}", 
								match.getMatchDate(), match.getMatchDay(), latestOdds.getHomeRate(), match.getMatchId(), match.getHomeTeam(), match.getAwayTeam(), 
								vsWin, vsDraw, vsWin + vsDraw + vsLose);
						analyseMatchup = true;
					}
				}
				if(latestOdds.getAwayRate().compareTo(new BigDecimal(Analytics.DOUBT_RATE.getValue())) > 0) {
					if((vsLose + vsDraw) / (double) (vsWin + vsDraw + vsLose) > Analytics.HIGH_WIN_POSSIBILITY.getValue()) {
						log.info("***[{} {}] Abnormal Away Rate {} ({},{} vs {}) with {}win, {}draw out of {}", 
								match.getMatchDate(), match.getMatchDay(), latestOdds.getAwayRate(), match.getMatchId(), match.getHomeTeam(), match.getAwayTeam(), 
								vsLose, vsDraw, vsWin + vsDraw + vsLose);
						analyseMatchup = true;
					}
				}
			}
			if(match.getLeague().getType().equals(FootballLeague.TYPE.LEAGUE) && analyseMatchup )
				executeAnalyseMatchup(match.getHomeTeam(), match.getAwayTeam(), match.getLeague());

		}
		
	}
	
	private void executeAnalyseMatchup(String homeTeam, String awayTeam, FootballLeague league) {
		List<FootballMatchup> homeMatchups = footballMatchDao.getMatchup(homeTeam, league.toString(), FootballMatch.MATCH_AT.HOME);

		Map<KeysObject, Integer> homeMatchesInSeason = homeMatchups.stream().collect(Collectors.groupingBy(m -> new KeysObject(m.getSeason(), m.getVsTier()), 
				Collectors.summingInt(FootballMatchup::getNumMatches)));

		for(FootballMatchup matchup : homeMatchups) {
			double rate = matchup.getNumMatches() / (double) homeMatchesInSeason.get(new KeysObject(matchup.getSeason(), matchup.getVsTier()));
			log.info("{} H:{} A:{} {} {}:{} total:{} Rate:{} vs:{}", matchup.getTeam(), matchup.getTeamTier(), matchup.getVsTier(), 
					String.format("%-10s", matchup.getSeason()), matchup.getResult(), 
					matchup.getNumMatches(), homeMatchesInSeason.get(new KeysObject(matchup.getSeason(), matchup.getVsTier())), rate, matchup.getVsTeamList());
		}
		
		List<FootballMatchup> awayMatchups = footballMatchDao.getMatchup(awayTeam, league.toString(), FootballMatch.MATCH_AT.AWAY);
		Map<KeysObject, Integer> awayMatchesInSeason = awayMatchups.stream().collect(Collectors.groupingBy(m -> new KeysObject(m.getSeason(), m.getVsTier()), 
				Collectors.summingInt(FootballMatchup::getNumMatches)));
		
		for(FootballMatchup matchup : awayMatchups) {
			double rate = matchup.getNumMatches() / (double) awayMatchesInSeason.get(new KeysObject(matchup.getSeason(), matchup.getVsTier()));
			log.info("{} H:{} A:{} {} {}:{} total:{} Rate:{} vs:{}", matchup.getTeam(), matchup.getTeamTier(), matchup.getVsTier(), 
					String.format("%-10s", matchup.getSeason()), matchup.getResult(), 
					matchup.getNumMatches(), awayMatchesInSeason.get(new KeysObject(matchup.getSeason(), matchup.getVsTier())), rate, matchup.getVsTeamList());
		}
		
	}
	
	@SuppressWarnings("unused")
	private void dummyExecuteAnalyseMatchup(String homeTeam, String awayTeam, FootballLeague league) {
		List<FootballMatchup> homeMatchups = footballMatchDao.getMatchup(homeTeam, league.toString(), FootballMatch.MATCH_AT.HOME);
		//Map<String, Integer> seasonTotalMatches = homeMatchups.stream().collect(Collectors.groupingBy(FootballMatchup::getSeason, Collectors.summingInt(FootballMatchup::getNumMatches)));
		Map<KeysObject, List<FootballMatchup>> map = homeMatchups.stream().collect(Collectors.groupingBy(m -> new KeysObject(m.getSeason(), m.getVsTier()), 
				Collectors.mapping((FootballMatchup m) -> m, Collectors.toList())));
				
		Map<KeysObject, List<FootballMatchup>> sortedMap = new TreeMap<KeysObject, List<FootballMatchup>>(
				new Comparator<KeysObject>() {
					@Override
					public int compare(KeysObject key1, KeysObject key2) {
						int result = Math.negateExact(((String)key1.getKeys().get(0)).compareTo(((String)key2.getKeys().get(0)))); //m.getSeason()
						if (result != 0) return result;
						result = ((Integer)key1.getKeys().get(1)).compareTo((Integer)key2.getKeys().get(1)); //m.getVsTier()
						return result;
					}
                }
			); 
		sortedMap.putAll(map);
		log.info("{}", sortedMap);
	}
}
