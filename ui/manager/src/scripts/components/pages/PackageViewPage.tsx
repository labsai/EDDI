import * as React from 'react';
import PackageInfo from '../PackageDetailView/PackageInfo';

interface IRouteProps {
  match: { params: { id: string; version: string } };
}
interface IProps extends IRouteProps {}

const PackageViewPage = (props: IProps) => (
  <div>
    <PackageInfo
      packageId={props.match.params.id}
      version={props.match.params.version}
    />
  </div>
);

export default PackageViewPage;
