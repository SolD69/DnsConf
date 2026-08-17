package com.novibe.dns.next_dns.service;

import com.novibe.common.util.Log;
import com.novibe.dns.next_dns.http.NextDnsDenyClient;
import com.novibe.dns.next_dns.http.NextDnsRateLimitedApiProcessor;
import com.novibe.dns.next_dns.http.dto.request.CreateDenyDto;
import com.novibe.dns.next_dns.http.dto.response.deny.DenyDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NextDnsDenyService {

    private final NextDnsDenyClient nextDnsDenyClient;

    public List<String> omitExistingDenys(List<String> newDenyList) {
        Log.io("Fetching existing denylist from NextDNS");
        List<DenyDto> existingDenyList = nextDnsDenyClient.fetchDenylist();
        Set<String> existingDomainsSet = existingDenyList.stream()
                .filter(DenyDto::isActive)
                .map(DenyDto::getId)
                .collect(Collectors.toSet());
        newDenyList.removeIf(existingDomainsSet::contains);
        return newDenyList;
    }

    /**
     * A denied domain never reaches the rewrite stage, so blocking a domain that is also redirected
     * silently breaks that redirect. Exact conflicts are dropped from the denylist in favour of the
     * redirect; a blocked parent domain cannot be dropped without discarding a block that was asked
     * for, so it is reported instead.
     */
    public List<String> omitRedirectedDomains(List<String> newDenyList, Set<String> redirectDomains) {
        if (redirectDomains.isEmpty()) {
            return newDenyList;
        }
        int before = newDenyList.size();
        newDenyList.removeIf(redirectDomains::contains);
        int dropped = before - newDenyList.size();
        if (dropped > 0) {
            Log.common("Skipped %s domains from denylist because they are redirected".formatted(dropped));
        }
        reportShadowedRedirects(newDenyList, redirectDomains);
        return newDenyList;
    }

    private void reportShadowedRedirects(List<String> denyList, Set<String> redirectDomains) {
        Set<String> denySet = new HashSet<>(denyList);
        Set<String> shadowed = new TreeSet<>();
        for (String redirectDomain : redirectDomains) {
            for (int dot = redirectDomain.indexOf('.'); dot >= 0; dot = redirectDomain.indexOf('.', dot + 1)) {
                String parent = redirectDomain.substring(dot + 1);
                if (denySet.contains(parent)) {
                    shadowed.add("%s is blocked, breaking redirect of %s".formatted(parent, redirectDomain));
                }
            }
        }
        if (!shadowed.isEmpty()) {
            Log.fail("Blocked parent domains will override redirects:\n" + String.join("\n", shadowed));
        }
    }

    public void saveDenyList(List<String> newDenylist) {
        List<CreateDenyDto> createRequests = newDenylist.stream().map(CreateDenyDto::new).toList();
        Log.io("Saving new denylist to NextDNS...");
        NextDnsRateLimitedApiProcessor.callApi(createRequests, nextDnsDenyClient::saveDeny);
    }

    public void removeAll() {
        Log.io("Fetching existing denylist from NextDNS");
        List<DenyDto> existing = nextDnsDenyClient.fetchDenylist();
        List<String> ids = existing.stream().map(DenyDto::getId).toList();
        Log.io("Removing denylist from NextDNS");
        NextDnsRateLimitedApiProcessor.callApi(ids, nextDnsDenyClient::deleteDenyById);
    }

}
