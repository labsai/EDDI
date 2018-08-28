import * as React from 'react';
import PackageInfo from '../PackageDetailView/PackageInfo';

interface IRouteProps {
  match: { params: { id: string } };
}
interface IProps extends IRouteProps {}

const PackageViewPage = (props: IProps) => (
  <div>
    <PackageInfo packageId={props.match.params.id} />
  </div>
);

export default PackageViewPage;
