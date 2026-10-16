package com.mmu.schedulingapp.service.autorouting;

/** Great-circle heuristic shared by allocation, ordering and labelled fallback metrics. */
final class GeographicDistance {
    private GeographicDistance() { }
    static double km(double lat1,double lon1,double lat2,double lon2) {
        double dLat=Math.toRadians(lat2-lat1),dLon=Math.toRadians(lon2-lon1);
        double value=Math.sin(dLat/2)*Math.sin(dLat/2)+Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(dLon/2)*Math.sin(dLon/2);
        double bounded=Math.max(0,Math.min(1,value));
        return 6371*2*Math.atan2(Math.sqrt(bounded),Math.sqrt(1-bounded));
    }
}
