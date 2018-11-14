import * as React from 'react';
import PackageInfo from '../PackageDetailView/PackageInfo';
import Parser from '../utils/Parser';

interface IRouteProps {
  match: { params: { id: string } };
  location: { pathname: string; search: string };
}
function getVersion(search: string) {
  const queryStrings = Parser.getQueryStrings(search);
  return queryStrings.version;
}
interface IProps extends IRouteProps {}

const PackageViewPage = (props: IProps) => (
  <div>
    <PackageInfo
      packageId={props.match.params.id}
      version={getVersion(props.location.search)}
    />
  </div>
);

export default PackageViewPage;
