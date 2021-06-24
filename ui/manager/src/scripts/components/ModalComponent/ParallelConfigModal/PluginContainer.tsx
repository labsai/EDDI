import * as _ from 'lodash';
import * as React from 'react';
import { useSelector } from 'react-redux';
import { IAppState } from 'src/scripts/reducers';
import { pluginSelector } from '../../../selectors/PluginSelectors';
import { IPlugin } from '../../utils/AxiosFunctions';
import EditJsonModal from '../EditJsonModal/EditJsonModal';
import ViewJsonContent from '../ViewJsonModal/ViewJsonContent';

interface IPublicProps {
  pluginResource: string;
  type: string;
}

const PluginContainer = (props: IPublicProps) => {
  const [isEdit, setIsEdit] = React.useState(false);
  const { plugin } = useSelector((state: IAppState) =>
    pluginSelector(state, { pluginResource: props.pluginResource }),
  );
  const [data, setData] = React.useState('');

  React.useEffect(() => {
    setData(JSON.stringify((plugin as IPlugin).pluginData, null, '\t'));
  }, [plugin]);

  if (_.isEmpty(plugin)) {
    return <></>;
  }

  return (
    <div>
      {!isEdit ? (
        <ViewJsonContent
          descriptor={plugin as IPlugin}
          data={data}
          usedBy={(plugin as IPlugin).usedByPackages}
          showEditJson={() => setIsEdit(true)}
        />
      ) : (
        <EditJsonModal
          type={props.type}
          descriptor={plugin as IPlugin}
          resource={(plugin as IPlugin).resource}
          data={data}
          showViewJson={() => setIsEdit(false)}
        />
      )}
    </div>
  );
};

export default PluginContainer;
