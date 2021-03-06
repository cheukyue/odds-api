package us.ceka.dto.impl;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.beanutils.BeanUtils;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Repository;

import us.ceka.domain.Standing;
import us.ceka.dto.StandingDto;

@Repository("standingDto")
public class StandingDtoImpl extends BaseDtoJsoupImpl<Standing> implements StandingDto {

	public List<Standing> getLeagueStanding(String seasonId, String leagueId) {
		List<Standing> list = new ArrayList<Standing>();
		Document doc = getJsoupTemplate().getDocumnetByAlias("url.standing", leagueId, seasonId);
		
		Elements teamElts = doc.select(".mainTable tr");
		for(Element teamNode : teamElts) {
			if(!"td".equalsIgnoreCase(teamNode.child(0).tagName())) continue;
			Map<String, Object> map = new HashMap<String, Object>();
			map.put("team", teamNode.child(2).text()); 
			map.put("rank", teamNode.child(0).text());
			map.put("played", teamNode.child(3).text());
			map.put("win", teamNode.child(4).text());
			map.put("draw", teamNode.child(5).text());
			map.put("lose", teamNode.child(6).text());
			map.put("goalFor", teamNode.child(7).text());
			map.put("goalAgainst", teamNode.child(8).text());
			map.put("point", teamNode.child(10).text());
			
			Standing standing = new Standing();
			try {
				BeanUtils.populate(standing, map);
			} catch (IllegalAccessException | InvocationTargetException e) {
				log.error("Error on populating value into FootballStanding", e);
			}
			
			if(log.isDebugEnabled()) log.debug("{}", standing);
			list.add(standing);
		}

		return list;
	}
}
